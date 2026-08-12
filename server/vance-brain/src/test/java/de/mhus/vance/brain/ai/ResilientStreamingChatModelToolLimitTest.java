package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The "too many tool schemas" path. Two things must hold, and both are
 * the opposite of the normal resilience behaviour:
 *
 * <ul>
 *   <li><b>Do not advance the chain.</b> The endpoint rejected the request
 *       <em>shape</em> before inference; every entry gets the identical
 *       manifest and answers identically. On 2026-08-12 that burned both
 *       chain entries on the same 400.</li>
 *   <li><b>Learn the number.</b> The message carries the cap the model
 *       catalog was missing, so the next turn's surface can fit.</li>
 * </ul>
 */
class ResilientStreamingChatModelToolLimitTest {

    private static final RetryPolicy FAST = new RetryPolicy(
            3, Duration.ofMillis(1), Duration.ofMillis(2), List.of());

    private static final String REJECTION =
            "{\"error\":{\"message\":\"Invalid 'tools': array too long. Expected an array "
                    + "with maximum length 128, but got an array with length 163 instead.\","
                    + "\"type\":\"invalid_request_error\",\"param\":\"tools\","
                    + "\"code\":\"array_above_max_length\"}}";

    private static ChatRequest requestWithTools(int count) {
        List<ToolSpecification> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            specs.add(ToolSpecification.builder().name("tool_" + i).description("d").build());
        }
        return ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .toolSpecifications(specs)
                .build();
    }

    private static StreamingChatModel failing(AtomicInteger calls, String message) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onError(new RuntimeException(message));
            }
        };
    }

    @Test
    void toolsTooLong_doesNotAdvanceTheChain() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(List.of(
                new ChainEntry(failing(primaryCalls, REJECTION), "openai:sol", FAST),
                new ChainEntry(failing(fallbackCalls, REJECTION), "openai:luna", FAST)));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(error, done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isZero();
        assertThat(error.get()).isInstanceOf(AiChatException.class);
        assertThat(error.get().getMessage()).contains("Tool manifest too large");
    }

    @Test
    void toolsTooLong_reportsLabelMessageAndCountToTheLearner() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> label = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AtomicInteger requested = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(failing(calls, REJECTION), "openai:sol", FAST)),
                /*userNotifier*/ null,
                (l, t, count) -> {
                    label.set(l);
                    text.set(t);
                    requested.set(count);
                    return OptionalInt.of(128);
                });

        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(new AtomicReference<>(), done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(label.get()).isEqualTo("openai:sol");
        assertThat(ToolLimitError.parseLimit(text.get())).hasValue(128);
        assertThat(requested.get()).isEqualTo(163);
    }

    @Test
    void learnedLimit_isNamedAndTheTurnIsWorthRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(failing(calls, REJECTION), "openai:sol", FAST)),
                null,
                (l, t, count) -> OptionalInt.of(128));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(error, done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get().getMessage())
                .contains("128")
                .contains("retry the turn");
    }

    @Test
    void nothingLearned_doesNotPromiseThatARetryHelps() throws Exception {
        // The next turn would build the identical manifest, so telling the
        // caller to retry sends them into the same 400. The message has to
        // point at the durable fix instead.
        AtomicInteger calls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(failing(calls, REJECTION), "openai:sol", FAST)),
                null,
                (l, t, count) -> OptionalInt.empty());

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(error, done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get().getMessage())
                .doesNotContain("retry the turn")
                .contains("maxTools");
    }

    @Test
    void withoutALearner_alsoDoesNotPromiseARetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(failing(calls, REJECTION), "openai:sol", FAST)));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(error, done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get().getMessage()).doesNotContain("retry the turn");
    }

    @Test
    void learnerThatThrows_doesNotChangeTheOutcome() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(failing(calls, REJECTION), "openai:sol", FAST)),
                null,
                (l, t, count) -> {
                    throw new IllegalStateException("registry down");
                });

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(163), errorOnly(error, done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(AiChatException.class);
    }

    @Test
    void otherNonRetriableError_stillAdvancesTheChain() throws Exception {
        // Guard against over-matching: a normal bad-request must keep the
        // existing fallback behaviour.
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(List.of(
                new ChainEntry(failing(primaryCalls, "model_not_found"), "openai:sol", FAST),
                new ChainEntry(failing(fallbackCalls, "model_not_found"), "openai:luna", FAST)));

        CountDownLatch done = new CountDownLatch(1);
        model.chat(requestWithTools(10), errorOnly(new AtomicReference<>(), done));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    private static StreamingChatResponseHandler errorOnly(
            AtomicReference<Throwable> sink, CountDownLatch done) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse r) {
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                sink.set(error);
                done.countDown();
            }
        };
    }
}
