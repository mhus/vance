package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ResilientChatModel} — retry, chain-advance, the deadline and the
 * answered-by report.
 *
 * <p>The sync path had none of this until it was written: a single 429
 * from the provider surfaced straight to every non-streaming caller, and
 * the tenant's configured fallbacks were never reached. These tests pin
 * the rules that decide when a call is worth repeating, because the cost
 * of getting them wrong is either a burnt fallback or a caller blocked
 * past its own timeout.
 */
class ResilientChatModelTest {

    /** Tiny backoff so retries don't slow the suite down. */
    private static final RetryPolicy FAST = new RetryPolicy(
            3, Duration.ofMillis(1), Duration.ofMillis(2), List.of("overloaded"));

    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("hi"))
            .build();

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static ChatResponse empty() {
        return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
    }

    private static ChatResponse truncatedEmpty() {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .finishReason(FinishReason.LENGTH)
                .build();
    }

    /**
     * Delegate playing a scripted sequence: a {@link ChatResponse} is
     * returned, a {@link RuntimeException} is thrown. The last element
     * repeats once the script runs out.
     */
    private static ChatModel scripted(AtomicInteger calls, Object... sequence) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                int i = calls.getAndIncrement();
                Object next = sequence[Math.min(i, sequence.length - 1)];
                if (next instanceof RuntimeException e) throw e;
                return (ChatResponse) next;
            }
        };
    }

    /** Delegate that takes its time, for the deadline cases. */
    private static ChatModel slow(AtomicInteger calls, long millis, ChatResponse reply) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                sleep(millis);
                return reply;
            }
        };
    }

    private static SyncChainEntry entry(String label, ChatModel model) {
        return new SyncChainEntry(model, label, FAST);
    }

    // ──────────────────── retry within one entry ────────────────────

    @Test
    void a_transient_failure_is_retried_on_the_same_entry() {
        AtomicInteger calls = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(List.of(entry("openai:a",
                scripted(calls, new RuntimeException("server overloaded"), response("ok")))));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void a_non_retriable_error_does_not_burn_the_attempt_budget() {
        AtomicInteger calls = new AtomicInteger();
        // "invalid api key" matches no retry pattern: repeating it just
        // asks the same question and gets the same answer.
        ResilientChatModel model = new ResilientChatModel(List.of(entry("openai:a",
                scripted(calls, new RuntimeException("invalid api key")))));

        assertThatThrownBy(() -> model.chat(REQUEST))
                .isInstanceOf(AiChatException.class)
                .hasMessageContaining("exhausted");
        assertThat(calls.get()).as("tried once, not three times").isEqualTo(1);
    }

    // ──────────────────── chain advance ────────────────────

    @Test
    void an_exhausted_entry_advances_to_the_fallback() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(List.of(
                entry("openai:a", scripted(first, new RuntimeException("overloaded"))),
                entry("ollama:b", scripted(second, response("from the fallback")))));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("from the fallback");
        assertThat(first.get()).as("primary spent its budget").isEqualTo(3);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    void all_entries_failing_surfaces_the_last_cause() {
        ResilientChatModel model = new ResilientChatModel(List.of(
                entry("openai:a", scripted(new AtomicInteger(),
                        new RuntimeException("overloaded"))),
                entry("ollama:b", scripted(new AtomicInteger(),
                        new RuntimeException("connection refused")))));

        assertThatThrownBy(() -> model.chat(REQUEST))
                .isInstanceOf(AiChatException.class)
                .hasMessageContaining("All 2 chat-model chain entries exhausted")
                .hasRootCauseMessage("connection refused");
    }

    // ──────────────────── empty replies ────────────────────

    @Test
    void an_empty_reply_is_retried_then_the_chain_advances() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(List.of(
                entry("openai:a", scripted(first, empty())),
                entry("ollama:b", scripted(second, response("real answer")))));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("real answer");
        assertThat(first.get())
                .as("empty budget is capped below the full attempt budget")
                .isEqualTo(3);
    }

    @Test
    void an_empty_reply_at_the_output_cap_is_not_retried() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger fallback = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(List.of(
                entry("openai:a", scripted(calls, truncatedEmpty())),
                entry("ollama:b", scripted(fallback, response("ok")))));

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        // finish=LENGTH is a wall, not a glitch — re-issuing the identical
        // request hits it identically and only burns tokens.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void when_every_entry_returns_empty_the_empty_response_is_delivered() {
        ResilientChatModel model = new ResilientChatModel(List.of(
                entry("openai:a", scripted(new AtomicInteger(), empty())),
                entry("ollama:b", scripted(new AtomicInteger(), truncatedEmpty()))));

        // Not an exception: the caller's own empty-reply handling stays in
        // charge, and the finish reason travels with it.
        ChatResponse delivered = model.chat(REQUEST);
        assertThat(delivered.aiMessage().text()).isEmpty();
        assertThat(delivered.finishReason()).isEqualTo(FinishReason.LENGTH);
    }

    // ──────────────────── deadline ────────────────────

    @Test
    void the_deadline_stops_the_retries_rather_than_running_them_out() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy slow = new RetryPolicy(
                5, Duration.ofSeconds(30), Duration.ofSeconds(60), List.of("overloaded"));
        ResilientChatModel model = new ResilientChatModel(
                List.of(new SyncChainEntry(
                        scripted(calls, new RuntimeException("overloaded")), "openai:a", slow)),
                null, null, Duration.ofMillis(50), null);

        long startMs = System.currentTimeMillis();
        assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOf(AiChatException.class);

        // The point of the deadline: a caller that has already committed
        // to a timeout must not be held for the full retry ladder.
        assertThat(System.currentTimeMillis() - startMs).isLessThan(5_000);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void the_deadline_does_not_cut_a_call_that_is_already_succeeding() {
        AtomicInteger calls = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(
                List.of(entry("openai:a", slow(calls, 60, response("slow but fine")))),
                null, null, Duration.ofMillis(10), null);

        // The budget bounds how long we keep *trying*. An in-flight request
        // belongs to the HTTP client's timeout; killing it here would drop
        // a good answer and leave the connection half-read.
        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("slow but fine");
        assertThat(calls.get()).isEqualTo(1);
    }

    // ──────────────────── reporting ────────────────────

    @Test
    void the_entry_that_answered_is_reported_not_the_one_asked_first() {
        AtomicReference<String> answered = new AtomicReference<>();
        ResilientChatModel model = new ResilientChatModel(
                List.of(entry("openai:a", scripted(new AtomicInteger(),
                                new RuntimeException("invalid api key"))),
                        entry("ollama:b", scripted(new AtomicInteger(), response("ok")))),
                null, null, null, answered::set);

        model.chat(REQUEST);

        // A consumer archiving the answer needs the model behind it. After
        // a fallback that is not the model the call started with.
        assertThat(answered.get()).isEqualTo("ollama:b");
    }

    @Test
    void nothing_is_reported_when_no_entry_answered() {
        AtomicReference<String> answered = new AtomicReference<>();
        ResilientChatModel model = new ResilientChatModel(
                List.of(entry("openai:a", scripted(new AtomicInteger(),
                        new RuntimeException("invalid api key")))),
                null, null, null, answered::set);

        assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOf(AiChatException.class);
        assertThat(answered.get()).isNull();
    }

    @Test
    void a_throwing_report_hook_does_not_break_the_call() {
        ResilientChatModel model = new ResilientChatModel(
                List.of(entry("openai:a", scripted(new AtomicInteger(), response("ok")))),
                null, null, null, label -> {
                    throw new IllegalStateException("sink is broken");
                });

        assertThat(model.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void retries_and_advances_are_narrated_to_the_notifier() {
        List<String> notes = new ArrayList<>();
        ResilientChatModel model = new ResilientChatModel(
                List.of(entry("openai:a", scripted(new AtomicInteger(),
                                new RuntimeException("overloaded"))),
                        entry("ollama:b", scripted(new AtomicInteger(), response("ok")))),
                notes::add, null, null, null);

        model.chat(REQUEST);

        assertThat(notes).anyMatch(n -> n.contains("retry 1/3"));
        assertThat(notes).anyMatch(n -> n.contains("falling back to ollama:b"));
    }

    // ──────────────────── construction ────────────────────

    @Test
    void an_empty_chain_is_rejected_at_construction() {
        Deque<SyncChainEntry> none = new ArrayDeque<>();
        assertThatThrownBy(() -> new ResilientChatModel(List.copyOf(none)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one entry");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
