package de.mhus.vance.brain.ai;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * Bridges provider {@link TokenUsage} subclasses that carry prompt-cache
 * counters without implementing {@link CacheAwareTokenUsage}.
 *
 * <p>{@code AnthropicTokenUsage} speaks the marker interface directly because
 * Vance owns that wire. The OpenAI and Gemini models come from langchain4j,
 * and their usage classes keep the cached counters in provider-specific
 * fields — {@code OpenAiTokenUsage.inputTokensDetails().cachedTokens()},
 * {@code GoogleAiGeminiTokenUsage.cachedContentTokenCount()} — which no
 * {@code instanceof CacheAwareTokenUsage} check in the accounting or trace
 * layer ever sees. Without this bridge every cached workload on those wires
 * booked cache reads as zero: the numbers existed, Vance just never looked.
 *
 * <p><b>Counting semantics differ per wire</b> and the bridge normalizes both
 * into the additive {@link CacheAwareTokenUsage} contract
 * ({@code inputTokenCount} = <i>uncached</i> input, cache counters on top):
 * <ul>
 *   <li>OpenAI's {@code prompt_tokens} <b>includes</b> the cached tokens, so
 *       the bridge subtracts them — otherwise the ledger would bill the
 *       cached share at full input price <i>and</i> again as cache reads.</li>
 *   <li>Gemini's {@code promptTokenCount} equally includes
 *       {@code cachedContentTokenCount}.</li>
 * </ul>
 * Anthropic counts the other way around ({@code input_tokens} is already
 * uncached), which is why its usage class needs no adjustment here.
 */
public final class CacheAwareTokenUsageAdapter {

    private CacheAwareTokenUsageAdapter() {}

    /**
     * The usage to account with — the original when it carries nothing to
     * normalize, a cache-aware wrapper when it does. Idempotent: a wrapped
     * usage already implements {@link CacheAwareTokenUsage} and passes
     * through unchanged, so a decorator applied twice cannot subtract the
     * cached share twice.
     */
    public static @Nullable TokenUsage map(@Nullable TokenUsage usage) {
        if (usage == null || usage instanceof CacheAwareTokenUsage) return usage;
        if (usage instanceof OpenAiTokenUsage openAi) return mapOpenAi(openAi);
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) return mapGemini(gemini);
        return usage;
    }

    private static TokenUsage mapOpenAi(OpenAiTokenUsage usage) {
        Integer cached = usage.inputTokensDetails() == null
                ? null
                : usage.inputTokensDetails().cachedTokens();
        if (cached == null || cached <= 0) return usage;
        return new AdjustedTokenUsage(
                uncached(value(usage.inputTokenCount()), cached), value(usage.outputTokenCount()), 0, cached);
    }

    private static TokenUsage mapGemini(GoogleAiGeminiTokenUsage usage) {
        Integer cached = usage.cachedContentTokenCount();
        if (cached == null || cached <= 0) return usage;
        return new AdjustedTokenUsage(
                uncached(value(usage.inputTokenCount()), cached), value(usage.outputTokenCount()), 0, cached);
    }

    private static int value(@Nullable Integer raw) {
        return raw == null || raw < 0 ? 0 : raw;
    }

    /**
     * Clamped subtraction — a provider that reports more cached tokens than
     * prompt tokens (broken proxy, mid-rollout field change) must not turn
     * the uncached count negative and bill a refund.
     */
    private static int uncached(int input, int cached) {
        return Math.max(0, input - cached);
    }

    /**
     * The normalized form both wires map onto. Same shape as
     * {@code AnthropicTokenUsage}: the standard counters carry uncached
     * input + output, the cache counters are additive on top. No wire
     * reports cache <i>writes</i> through these fields (OpenAI bills
     * caching implicitly, Gemini has no write counter) — the write side
     * stays {@code 0} and remains an Anthropic-only fact.
     */
    private static final class AdjustedTokenUsage extends TokenUsage implements CacheAwareTokenUsage {

        private final long cacheCreationInputTokens;
        private final long cacheReadInputTokens;

        AdjustedTokenUsage(int uncachedInput, int output, long cacheCreationInputTokens, long cacheReadInputTokens) {
            super(uncachedInput, output, uncachedInput + output);
            this.cacheCreationInputTokens = cacheCreationInputTokens;
            this.cacheReadInputTokens = cacheReadInputTokens;
        }

        @Override
        public long cacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        @Override
        public long cacheReadInputTokens() {
            return cacheReadInputTokens;
        }
    }
}
