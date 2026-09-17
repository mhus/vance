package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What the decorators promise: every reader above them — the ledger, the
 * trace log, the progress feed — sees the same normalized usage, without
 * knowing which langchain4j class a provider happened to return. The
 * stack test pins the reason the decorator sits innermost: accounting
 * bills from what it reads.
 */
class CacheAwareUsageChatModelTest {

    @Test
    void theCallerSeesTheNormalizedUsageAndAnIntactResponse() {
        ChatResponse raw = response(OpenAiTokenUsage.builder()
                .inputTokenCount(20_000)
                .outputTokenCount(300)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(18_000)
                        .build())
                .build());

        ChatResponse out = new CacheAwareUsageChatModel(new FakeChatModel(request -> raw)).chat(request());

        assertThat(out.tokenUsage()).isInstanceOf(CacheAwareTokenUsage.class);
        assertThat(out.tokenUsage().inputTokenCount()).isEqualTo(2_000);
        assertThat(out.tokenUsage().outputTokenCount()).isEqualTo(300);
        assertThat(((CacheAwareTokenUsage) out.tokenUsage()).cacheReadInputTokens())
                .isEqualTo(18_000);
        // The rebuild must not lose the payload — the caller still reads the
        // answer text through the same response object shape.
        assertThat(out.aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void aResponseWithNothingToMapIsPassedThroughUntouched() {
        // No allocation, no new metadata — and observable as identity.
        ChatResponse raw = response(new TokenUsage(10, 5));

        assertThat(new CacheAwareUsageChatModel(new FakeChatModel(request -> raw)).chat(request()))
                .isSameAs(raw);
    }

    @Test
    void streamingNormalizesTheFinalCompleteResponse() {
        ChatResponse raw = response(OpenAiTokenUsage.builder()
                .inputTokenCount(9_000)
                .outputTokenCount(90)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(8_000)
                        .build())
                .build());

        RecordingHandler handler = new RecordingHandler();
        new CacheAwareUsageStreamingChatModel(new FakeStreamingChatModel(raw)).chat(request(), handler);

        assertThat(handler.complete).isNotNull();
        assertThat(handler.complete.tokenUsage()).isInstanceOf(CacheAwareTokenUsage.class);
        assertThat(handler.complete.tokenUsage().inputTokenCount()).isEqualTo(1_000);
        assertThat(((CacheAwareTokenUsage) handler.complete.tokenUsage()).cacheReadInputTokens())
                .isEqualTo(8_000);
    }

    @Test
    void accountingOverTheCacheDecoratorBooksTheSplit() {
        // The wiring order from AbstractChatProvider: accounting wraps the
        // cache decorator. The ledger must receive the uncached input and
        // the cache reads separately — this is the double-billing guard,
        // end to end.
        RecordingSink sink = new RecordingSink();
        ChatModel stack = new UsageAccountingChatModel(
                new CacheAwareUsageChatModel(new FakeChatModel(request -> response(OpenAiTokenUsage.builder()
                        .inputTokenCount(20_000)
                        .outputTokenCount(300)
                        .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                                .cachedTokens(18_000)
                                .build())
                        .build()))),
                ATTRIBUTION,
                pricedModel(),
                "cortecs",
                sink);

        stack.chat(request());

        assertThat(sink.calls).hasSize(1);
        UsageMeasurement m = sink.calls.get(0);
        assertThat(m.tokensIn()).isEqualTo(2_000);
        assertThat(m.tokensOut()).isEqualTo(300);
        assertThat(m.cacheReadTokens()).isEqualTo(18_000);
        assertThat(m.cacheWriteTokens()).isZero();
    }

    @Test
    void accountingDetectsImplicitCacheFromTheBilledTail() {
        // The tail-billing gateway shape: a huge request (system prompt,
        // tool schemas) reports a small input token count with no cache
        // counters anywhere. The savings must land in the estimate field,
        // separately from the measured cache reads.
        RecordingSink sink = new RecordingSink();
        String bigSystem = "The quick brown fox jumps over the lazy dog. ".repeat(400);
        ChatRequest bigRequest = ChatRequest.builder()
                .messages(SystemMessage.from(bigSystem), UserMessage.from("hi"))
                .toolSpecifications(ToolSpecification.builder()
                        .name("tool_a")
                        .description("Does a thing. ".repeat(120))
                        .build())
                .build();
        ChatModel stack = new UsageAccountingChatModel(
                new CacheAwareUsageChatModel(new FakeChatModel(req -> response(new TokenUsage(300, 50)))),
                ATTRIBUTION,
                pricedModel(),
                "cortecs",
                sink);

        stack.chat(bigRequest);

        assertThat(sink.calls).hasSize(1);
        UsageMeasurement m = sink.calls.get(0);
        assertThat(m.tokensIn()).isEqualTo(300);
        assertThat(m.cacheReadTokens()).isZero(); // nothing measured
        // The request carried far more than the 300 reported tokens:
        // 18k chars of system + ~1.5k chars of tool schema ≈ 4.9k tokens,
        // minus the reported 300 → the gap is the estimate.
        assertThat(m.implicitCacheReadTokens()).isGreaterThan(1_000).isLessThan(6_000);
    }
    // ──────────────────── helpers ────────────────────

    private static final CallAttribution ATTRIBUTION =
            new CallAttribution("acme", "demo", "sess-1", "proc-1", "jeltz", "extract");

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    private static ChatResponse response(@Nullable TokenUsage usage) {
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(AiMessage.from("ok"));
        if (usage != null) b.tokenUsage(usage);
        return b.build();
    }

    private static ModelInfo pricedModel() {
        return new ModelInfo(
                "openai",
                "kimi-k3",
                200_000,
                8192,
                ModelSize.LARGE,
                java.util.Set.<ModelCapability>of(),
                60,
                2,
                false,
                null,
                new ModelInfo.Pricing("EUR", 2.693, 13.464, 0.3, 3.75),
                OutputTokenParam.MAX_TOKENS,
                java.util.Set.<SamplingParam>of(),
                null,
                null,
                false);
    }

    /** {@link ChatModel} is not a functional interface — hand-rolled stub. */
    private static final class FakeChatModel implements ChatModel {
        private final Function<ChatRequest, ChatResponse> behaviour;

        FakeChatModel(Function<ChatRequest, ChatResponse> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return behaviour.apply(request);
        }
    }

    /** Single-shot streaming stub: forwards the prepared complete response.
     * {@link StreamingChatModel} is not a functional interface either. */
    private static final class FakeStreamingChatModel implements StreamingChatModel {
        private final ChatResponse complete;

        FakeStreamingChatModel(ChatResponse complete) {
            this.complete = complete;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(complete);
        }
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private ChatResponse complete;

        @Override
        public void onCompleteResponse(ChatResponse complete) {
            this.complete = complete;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError("stream should not fail in this test", error);
        }
    }

    private static final class RecordingSink implements UsageSink {
        private final List<UsageMeasurement> calls = new ArrayList<>();

        @Override
        public void onCall(CallAttribution attribution, UsageMeasurement measurement) {
            calls.add(measurement);
        }
    }
}
