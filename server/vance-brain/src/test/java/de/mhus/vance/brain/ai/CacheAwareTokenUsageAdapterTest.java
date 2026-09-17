package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * The counting semantics of the two wires the adapter bridges — pinned here
 * because they are the difference between a correct invoice and a
 * plausible-looking one.
 *
 * <p>OpenAI and Gemini count cached tokens <i>inside</i> their prompt-token
 * totals; the ledger's price model treats {@code tokensIn} as uncached and
 * bills cache reads separately. The adapter is the one place that
 * translates, and each mapping rule here guards against the double-billing
 * (cached share paid at full input price <i>and</i> as cache read) or the
 * refund (uncached count going negative) that a wrong translation produces.
 */
class CacheAwareTokenUsageAdapterTest {

    @Test
    void openAiCachedTokensAreSplitOffThePromptTotal() {
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(50_000)
                .outputTokenCount(1_200)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(42_000)
                        .build())
                .build();

        TokenUsage mapped = CacheAwareTokenUsageAdapter.map(usage);

        assertThat(mapped).isNotSameAs(usage);
        assertThat(mapped).isInstanceOf(CacheAwareTokenUsage.class);
        // The split that keeps the invoice honest: 8k uncached input billed
        // at full price, 42k cache reads at the cache-read rate — not 50k
        // at full price plus 42k again.
        assertThat(mapped.inputTokenCount()).isEqualTo(8_000);
        assertThat(mapped.outputTokenCount()).isEqualTo(1_200);
        assertThat(((CacheAwareTokenUsage) mapped).cacheReadInputTokens()).isEqualTo(42_000);
        assertThat(((CacheAwareTokenUsage) mapped).cacheCreationInputTokens()).isZero();
    }

    @Test
    void openAiWithoutCacheDetailsPassesThroughUnchanged() {
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(500)
                .outputTokenCount(50)
                .build();

        assertThat(CacheAwareTokenUsageAdapter.map(usage)).isSameAs(usage);
    }

    @Test
    void geminiCachedContentIsSplitOffThePromptTotal() {
        GoogleAiGeminiTokenUsage usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(30_000)
                .outputTokenCount(500)
                .cachedContentTokenCount(25_000)
                .build();

        TokenUsage mapped = CacheAwareTokenUsageAdapter.map(usage);

        assertThat(mapped).isInstanceOf(CacheAwareTokenUsage.class);
        assertThat(mapped.inputTokenCount()).isEqualTo(5_000);
        assertThat(mapped.outputTokenCount()).isEqualTo(500);
        assertThat(((CacheAwareTokenUsage) mapped).cacheReadInputTokens()).isEqualTo(25_000);
    }

    @Test
    void aProviderReportingMoreCachedThanPromptClampsAtZero() {
        // Defensive against a broken proxy or a mid-rollout field change:
        // the uncached share must not go negative — that would be a refund.
        GoogleAiGeminiTokenUsage usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(100)
                .outputTokenCount(10)
                .cachedContentTokenCount(500)
                .build();

        TokenUsage mapped = CacheAwareTokenUsageAdapter.map(usage);

        assertThat(mapped.inputTokenCount()).isZero();
        assertThat(((CacheAwareTokenUsage) mapped).cacheReadInputTokens()).isEqualTo(500);
    }

    @Test
    void alreadyCacheAwareUsagePassesThroughUnchanged() {
        // Anthropic's wire speaks the marker interface with correct
        // semantics already — and idempotence is what protects a decorator
        // applied twice from subtracting the cached share twice.
        AnthropicTokenUsage usage = new AnthropicTokenUsage(300, 120, 5_000, 40_000);

        assertThat(CacheAwareTokenUsageAdapter.map(usage)).isSameAs(usage);
    }

    @Test
    void plainTokenUsagePassesThroughUnchanged() {
        // Ollama, LM Studio and every backend without cache counters.
        TokenUsage usage = new TokenUsage(10, 5);

        assertThat(CacheAwareTokenUsageAdapter.map(usage)).isSameAs(usage);
    }

    @Test
    void nullStaysNull() {
        assertThat(CacheAwareTokenUsageAdapter.map(null)).isNull();
    }
}
