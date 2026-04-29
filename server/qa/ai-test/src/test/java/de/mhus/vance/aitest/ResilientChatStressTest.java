package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.VanceBrainApplication;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehavior;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end stress test for the {@code ResilientStreamingChatModel}
 * layer. Drives the wrapper through a synthetic {@link FailingAiProvider}
 * that fails on demand, so we can assert retry counts, success-after-fail
 * and non-retriable propagation without depending on a real provider's
 * willingness to misbehave.
 *
 * <p>This is independent of the Pet-Clinic test because the concerns are
 * disjoint: this one stress-tests the resilient layer; that one tests
 * the autonomous-build flow.
 */
@SpringBootTest(
        classes = {VanceBrainApplication.class, FailingAiProvider.class},
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResilientChatStressTest {

    @Autowired
    private AiModelService aiModelService;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        AbstractAiTest.wipeAiTestArtifacts();
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @BeforeEach
    void resetCounters() {
        FailingAiProvider.resetCounters();
    }

    /**
     * With {@code RetryPolicy.DEFAULT.maxAttempts == 5} and an always-503
     * model, the resilient layer should issue exactly 5 attempts before
     * propagating the failure as {@link AiChatException}. End-to-end the
     * caller sees one error, not five.
     */
    @Test
    void always503_exhaustsRetriesThenPropagates() throws Exception {
        AiChat chat = aiModelService.createChat(
                new AiChatConfig("failing", "always-503", "n/a"),
                AiChatOptions.defaults());

        Throwable err = streamAndCaptureError(chat, Duration.ofMinutes(3));

        assertThat(err)
                .as("after retries are exhausted, caller sees an AiChatException")
                .isNotNull()
                .isInstanceOf(AiChatException.class);
        assertThat(FailingAiProvider.callCount("always-503"))
                .as("primary entry should be retried up to maxAttempts (5)")
                .isEqualTo(5);
    }

    /**
     * With a {@code 3-then-ok} model, the layer fails 3× then succeeds on
     * the 4th attempt. The caller receives a clean completion.
     */
    @Test
    void failThriceThenOk_completesAfterRetries() throws Exception {
        AiChat chat = aiModelService.createChat(
                new AiChatConfig("failing", "3-then-ok", "n/a"),
                AiChatOptions.defaults());

        ChatResponse response = streamAndCaptureResponse(chat, Duration.ofMinutes(3));

        assertThat(response)
                .as("call should complete on the 4th attempt")
                .isNotNull();
        assertThat(response.aiMessage().text())
                .isEqualTo("ok-from-failing-provider");
        assertThat(FailingAiProvider.callCount("3-then-ok"))
                .as("primary entry should be hit 4 times: 3 fails + 1 success")
                .isEqualTo(4);
    }

    /**
     * Phase B: a two-entry chain where the primary always fails (retriable)
     * and the fallback always succeeds. The chain should advance to the
     * fallback after the primary's budget is exhausted, and the caller
     * sees a clean completion.
     */
    @Test
    void chainFallback_primaryExhausted_secondaryServes() throws Exception {
        ChatBehavior behavior = new ChatBehavior(java.util.List.of(
                new ChatBehavior.Entry(
                        new AiChatConfig("failing", "always-503", "n/a"),
                        "primary"),
                new ChatBehavior.Entry(
                        new AiChatConfig("failing", "always-ok", "n/a"),
                        "fallback")));
        AiChat chat = aiModelService.createChat(behavior, AiChatOptions.defaults());

        ChatResponse response = streamAndCaptureResponse(chat, Duration.ofMinutes(3));

        assertThat(response)
                .as("call should complete via the fallback after primary exhausts retries")
                .isNotNull();
        assertThat(response.aiMessage().text())
                .isEqualTo("ok-from-failing-provider");
        assertThat(FailingAiProvider.callCount("always-503"))
                .as("primary should burn its full retry budget (5)")
                .isEqualTo(5);
        assertThat(FailingAiProvider.callCount("always-ok"))
                .as("fallback should serve exactly one call")
                .isEqualTo(1);
    }

    /**
     * Phase B: a two-entry chain where both entries always fail. Final
     * outcome is a clean {@link AiChatException}, not a raw provider
     * exception.
     */
    @Test
    void chainFallback_allEntriesExhausted_propagates() throws Exception {
        ChatBehavior behavior = new ChatBehavior(java.util.List.of(
                new ChatBehavior.Entry(
                        new AiChatConfig("failing", "always-503", "n/a"),
                        "primary"),
                new ChatBehavior.Entry(
                        new AiChatConfig("failing", "always-overloaded", "n/a"),
                        "fallback")));
        AiChat chat = aiModelService.createChat(behavior, AiChatOptions.defaults());

        Throwable err = streamAndCaptureError(chat, Duration.ofMinutes(5));

        assertThat(err)
                .as("after all chain entries fail, caller sees AiChatException")
                .isNotNull()
                .isInstanceOf(AiChatException.class);
        assertThat(FailingAiProvider.callCount("always-503"))
                .as("primary burns 5 attempts before chain advances")
                .isEqualTo(5);
        assertThat(FailingAiProvider.callCount("always-overloaded"))
                .as("fallback also burns 5 attempts before chain runs out")
                .isEqualTo(5);
    }

    /**
     * Non-retriable errors (e.g. INVALID_ARGUMENT, model not found) must
     * propagate immediately — no point burning retry budget on something
     * that won't fix itself.
     */
    @Test
    void nonRetriableError_propagatesWithoutRetry() throws Exception {
        AiChat chat = aiModelService.createChat(
                new AiChatConfig("failing", "non-retriable", "n/a"),
                AiChatOptions.defaults());

        Throwable err = streamAndCaptureError(chat, Duration.ofSeconds(10));

        assertThat(err)
                .as("non-retriable errors propagate without retry")
                .isNotNull()
                .isInstanceOf(AiChatException.class);
        assertThat(FailingAiProvider.callCount("non-retriable"))
                .as("no retries on non-retriable errors")
                .isEqualTo(1);
    }

    // ──────────────────── helpers ────────────────────

    /** Streams a one-shot question and waits for either success or error. */
    private static ChatResponse streamAndCaptureResponse(AiChat chat, Duration timeout)
            throws Exception {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) { /* ignore */ }
            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }
            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        };
        chat.streamingChatModel().chat(buildRequest("hello"), handler);
        return done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Streams a question and waits for an error. Returns the error, or
     * raises {@link AssertionError} if the call unexpectedly completed.
     */
    private static Throwable streamAndCaptureError(AiChat chat, Duration timeout)
            throws Exception {
        try {
            ChatResponse r = streamAndCaptureResponse(chat, timeout);
            throw new AssertionError("expected error, got success: "
                    + r.aiMessage().text());
        } catch (java.util.concurrent.ExecutionException ee) {
            return ee.getCause();
        }
    }

    private static ChatRequest buildRequest(String content) {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from(content)))
                .build();
    }
}
