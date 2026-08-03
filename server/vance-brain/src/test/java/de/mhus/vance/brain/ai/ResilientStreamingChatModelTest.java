package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Retry behaviour of {@link ResilientStreamingChatModel} for the
 * successful-but-empty completion path — the Gemini-style empty response
 * that arrives via {@code onCompleteResponse} rather than {@code onError}
 * and therefore bypasses the exception-based retry.
 */
class ResilientStreamingChatModelTest {

    /** Tiny backoff so the scheduled retries don't slow the test down. */
    private static final RetryPolicy FAST = new RetryPolicy(
            3, Duration.ofMillis(1), Duration.ofMillis(2), List.of());

    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("hi"))
            .build();

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /** Delegate that plays a scripted sequence of completions, one per call. */
    private static StreamingChatModel scripted(AtomicInteger calls, ChatResponse... sequence) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                int i = calls.getAndIncrement();
                handler.onCompleteResponse(sequence[Math.min(i, sequence.length - 1)]);
            }
        };
    }

    @Test
    void emptyCompletion_isRetried_andRecoveredReplyReachesCaller() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel delegate = scripted(calls, response(""), response("real answer"));
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(delegate, "primary", FAST)));

        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(REQUEST, completeOnly(delivered, done));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get()).isEqualTo("real answer");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void persistentEmpty_isDeliveredAsEmpty_notAsError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel delegate = scripted(calls, response(""));
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(delegate, "primary", FAST)));

        AtomicReference<String> delivered = new AtomicReference<>();
        AtomicReference<Throwable> errored = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        model.chat(REQUEST, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String partial) { }
            @Override public void onCompleteResponse(ChatResponse complete) {
                delivered.set(complete.aiMessage().text());
                done.countDown();
            }
            @Override public void onError(Throwable error) {
                errored.set(error);
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errored.get()).isNull();
        assertThat(delivered.get()).isEmpty();
        // min(maxAttempts=3, EMPTY_MAX_ATTEMPTS=3) = 3 total tries.
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void emptyAfterEmittedPartial_isNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onPartialResponse("partial ");
                handler.onCompleteResponse(response(""));
            }
        };
        ResilientStreamingChatModel model = new ResilientStreamingChatModel(
                List.of(new ChainEntry(delegate, "primary", FAST)));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> delivered = new AtomicReference<>();
        model.chat(REQUEST, completeOnly(delivered, done));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // Partial already streamed → a re-issue would duplicate output, so
        // the empty completion is delivered as-is without retry.
        assertThat(calls.get()).isEqualTo(1);
        assertThat(delivered.get()).isEmpty();
    }

    private static StreamingChatResponseHandler completeOnly(
            AtomicReference<String> delivered, CountDownLatch done) {
        return new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String partial) { }
            @Override public void onCompleteResponse(ChatResponse complete) {
                delivered.set(complete.aiMessage().text());
                done.countDown();
            }
            @Override public void onError(Throwable error) {
                done.countDown();
            }
        };
    }
}
