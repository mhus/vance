package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.anthropic.AnthropicTokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * The implicit-cache inference: a gateway that bills only the uncached
 * tail and reports no cache counters gets its savings detected from the
 * gap between the request's actual volume and the reported input. The
 * rule is generic — no provider name anywhere — and the conditions are
 * strict, because a wrong guess must never masquerade as a measurement.
 */
class ImplicitCacheEstimatorTest {

    private static final String BIG = "The quick brown fox jumps over the lazy dog. ".repeat(300);

    private static ChatRequest request(String systemText, String userText) {
        return ChatRequest.builder()
                .messages(SystemMessage.from(systemText), UserMessage.from(userText))
                .build();
    }

    @Test
    void aTailBilledCallRevealsItsCacheFromTheGap() {
        // 13.5k chars ≈ 3.4k estimated tokens, reported 300 → gap well over
        // the threshold in both directions (fraction and absolute).
        ChatRequest request = request(BIG, "hi");

        int implicit = ImplicitCacheEstimator.estimate(request, new TokenUsage(300, 10));

        assertThat(implicit).isGreaterThan(1_000).isLessThan(4_000);
    }

    @Test
    void toolSchemasCountTowardTheVolume() {
        // 128 schemas ≈ 36k tokens sit ahead of the messages — a gateway
        // caching them hides that volume from the reported count too.
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(BIG), UserMessage.from("hi"))
                .toolSpecifications(java.util.List.of(ToolSpecification.builder()
                        .name("tool_a")
                        .description("Does a thing. ".repeat(60))
                        .build()))
                .build();

        int implicit = ImplicitCacheEstimator.estimate(request, new TokenUsage(300, 10));

        int withoutTools = ImplicitCacheEstimator.estimate(request(BIG, "hi"), new TokenUsage(300, 10));
        assertThat(implicit).isGreaterThan(withoutTools);
    }

    @Test
    void aFullyReportedCallHasNothingToInfer() {
        // Honest accounting: reported ≥ half the estimate → no inference,
        // however much the estimate might wobble.
        ChatRequest request = request(BIG, "hi");

        assertThat(ImplicitCacheEstimator.estimate(request, new TokenUsage(10_000, 10)))
                .isZero();
        assertThat(ImplicitCacheEstimator.estimate(request, new TokenUsage(3_400, 10)))
                .isZero();
    }

    @Test
    void aSmallGapIsEstimationNoiseNotCache() {
        // Below the absolute floor the gap could be tokenizer jitter.
        ChatRequest request = request("a moderately sized system prompt. ".repeat(30), "hi");

        assertThat(ImplicitCacheEstimator.estimate(request, new TokenUsage(150, 10)))
                .isZero();
    }

    @Test
    void usageThatAlreadyCarriesCacheCountersIsMeasuredNotEstimated() {
        // A provider that itemizes its cache (Anthropic direct, or the
        // OpenAI bridge) is booked from the measurement — an estimate on
        // top would double-count.
        ChatRequest request = request(BIG, "hi");

        assertThat(ImplicitCacheEstimator.estimate(request, new AnthropicTokenUsage(300, 10, 0, 3_000)))
                .isZero();

        assertThat(ImplicitCacheEstimator.estimate(
                        request,
                        OpenAiTokenUsage.builder()
                                .inputTokenCount(300)
                                .outputTokenCount(10)
                                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                                        .cachedTokens(3_000)
                                        .build())
                                .build()))
                // The bridge turns this into a CacheAwareTokenUsage before
                // the estimator runs — a raw OpenAiTokenUsage with details
                // still infers, because nothing has claimed the cached
                // share yet.
                .isGreaterThan(1_000);
    }

    @Test
    void noUsageOrNoInputMeansNothingToInfer() {
        ChatRequest request = request(BIG, "hi");

        assertThat(ImplicitCacheEstimator.estimate(request, null)).isZero();
        assertThat(ImplicitCacheEstimator.estimate(request, new TokenUsage(0, 10)))
                .isZero();
        assertThat(ImplicitCacheEstimator.estimate(null, new TokenUsage(300, 10)))
                .isZero();
    }
}
