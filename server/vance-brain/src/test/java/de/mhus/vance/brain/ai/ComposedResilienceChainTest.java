package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The <em>composed</em> sync stack: the per-entry {@link ResilientChatModel}
 * that {@code StandardAiChat} builds, with the chain-wide advance-only one
 * from {@code ChainedAiChat} around it.
 *
 * <p>Every other test in this package drives a single layer. That is why
 * nobody noticed that a wall-clock deadline handed to both of them was
 * measured twice — entry one stopping just short of the budget, entry two
 * starting over with a fresh one, and a 90-second promise answering after
 * 180. What has to hold is that the budget belongs to the <em>call</em>,
 * not to a layer.
 */
class ComposedResilienceChainTest {

    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("hi"))
            .build();

    /**
     * Flat 400ms backoff against a 600ms budget: entry one can afford
     * exactly one retry, and nothing after it can afford any. Chosen so
     * the outcome follows from the arithmetic rather than from how fast
     * the machine happens to be.
     */
    private static final RetryPolicy SLOW_BACKOFF = new RetryPolicy(
            3, Duration.ofMillis(400), Duration.ofMillis(400), List.of("overloaded"));

    /** Same shape, negligible backoff — for the cases that are not about time. */
    private static final RetryPolicy FAST_BACKOFF = new RetryPolicy(
            3, Duration.ofMillis(1), Duration.ofMillis(1), List.of("overloaded"));

    /** What {@code ChainedAiChat} puts around the entries: try once, then move on. */
    private static final RetryPolicy ADVANCE_ONLY = new RetryPolicy(
            1, Duration.ofMillis(1), Duration.ofMillis(1), List.of());

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /** Always fails with a retriable error, counting the attempts. */
    private static ChatModel alwaysOverloaded(AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                throw new RuntimeException("server overloaded");
            }
        };
    }

    private static ChatModel answers(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return response(text);
            }
        };
    }

    /**
     * The real stack shape: each raw model gets the single-entry resilient
     * wrap {@code StandardAiChat} builds, and one advance-only instance
     * goes around all of them, the way {@code ChainedAiChat} does it. Both
     * levels are configured from the same {@code AiChatOptions}, hence the
     * same deadline on both.
     */
    private static ChatModel composed(
            Duration deadline, RetryPolicy innerPolicy, List<ChatModel> raw) {
        List<SyncChainEntry> outerChain = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String label = "entry-" + i;
            ChatModel inner = new ResilientChatModel(
                    List.of(new SyncChainEntry(raw.get(i), label, innerPolicy)),
                    null, null, deadline, null);
            outerChain.add(new SyncChainEntry(inner, label, ADVANCE_ONLY));
        }
        return new ResilientChatModel(outerChain, null, null, deadline, null);
    }

    @Test
    void theDeadlineIsSpentOnce_notOncePerChainEntry() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();
        ChatModel model = composed(Duration.ofMillis(600), SLOW_BACKOFF,
                List.of(alwaysOverloaded(first),
                        alwaysOverloaded(second),
                        alwaysOverloaded(third)));

        long startMs = System.currentTimeMillis();
        assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOf(AiChatException.class);
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Entry one uses its one affordable retry (400ms of the 600ms
        // budget). What is left cannot pay for another backoff, so every
        // later entry gets exactly one attempt.
        assertThat(first.get()).isEqualTo(2);
        assertThat(second.get()).as("must not restart the clock").isEqualTo(1);
        assertThat(third.get()).as("must not restart the clock").isEqualTo(1);
        assertThat(elapsedMs).as("one 400ms backoff, not one per entry").isLessThan(900);
    }

    @Test
    void anExhaustedEntry_stillAdvancesWhenTheBudgetAllows() {
        // The shared budget must not turn into a reason never to reach the
        // fallback: with time left, the chain behaves exactly as before.
        AtomicInteger first = new AtomicInteger();
        ChatModel model = composed(Duration.ofSeconds(30), FAST_BACKOFF,
                List.of(alwaysOverloaded(first), answers("from the fallback")));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("from the fallback");
        assertThat(first.get()).as("inner retries still happen").isEqualTo(3);
    }

    @Test
    void withoutADeadline_bothLayersBehaveAsBefore() {
        AtomicInteger first = new AtomicInteger();
        ChatModel model = composed(null, FAST_BACKOFF,
                List.of(alwaysOverloaded(first), answers("ok")));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        assertThat(first.get()).isEqualTo(3);
    }

    @Test
    void theCallScopedBudgetDoesNotLeakIntoTheNextCall() {
        // The shared instant lives for one chat() and is removed by the
        // instance that installed it. A second call on the same thread has
        // to get a full budget, not the expired one.
        AtomicInteger first = new AtomicInteger();
        ChatModel model = composed(Duration.ofSeconds(30), FAST_BACKOFF,
                List.of(alwaysOverloaded(first), answers("ok")));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        first.set(0);

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        assertThat(first.get()).as("second call retries as fully as the first").isEqualTo(3);
    }
}
