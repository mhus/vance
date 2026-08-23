package de.mhus.vance.brain.ai;

import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * Turns a langchain4j {@link TokenUsage} into a {@link UsageMeasurement}.
 * Shared by the sync and streaming accounting decorators so both read the
 * provider's numbers the same way.
 *
 * <p><b>Cache tokens are read here.</b> They were hardcoded to zero in both
 * former writers while {@code AnthropicTokenUsage} had been reporting them
 * all along — and because they are <i>additive</i> to
 * {@code inputTokenCount}, a cached workload was billed for a fraction of
 * what it actually sent.
 */
final class UsageAccounting {

    private UsageAccounting() {}

    /**
     * Measurement for a successful attempt, or {@code null} when the provider
     * reported no usage at all — there is nothing to book, and inventing a
     * zero row would inflate the call count without adding information.
     */
    static @Nullable UsageMeasurement succeeded(
            ModelInfo model,
            String providerInstance,
            int attempt,
            @Nullable TokenUsage usage,
            long durationMs) {
        int in = nonNegative(usage == null ? null : usage.inputTokenCount());
        int out = nonNegative(usage == null ? null : usage.outputTokenCount());
        int cacheRead = 0;
        int cacheWrite = 0;
        if (usage instanceof CacheAwareTokenUsage cau) {
            cacheRead = (int) Math.max(0, cau.cacheReadInputTokens());
            cacheWrite = (int) Math.max(0, cau.cacheCreationInputTokens());
        }
        if (in <= 0 && out <= 0 && cacheRead <= 0 && cacheWrite <= 0) {
            return null;
        }
        return UsageMeasurement.chat(
                model, providerInstance, attempt, in, out, cacheRead, cacheWrite, durationMs);
    }

    /**
     * Measurement for a failed attempt. Always produced, even without token
     * counts: the point of the row is that an attempt happened and probably
     * cost something the provider will not tell us about.
     */
    static UsageMeasurement failed(
            ModelInfo model, String providerInstance, int attempt, long durationMs) {
        return UsageMeasurement.chatFailed(model, providerInstance, attempt, 0, 0, durationMs);
    }

    private static int nonNegative(@Nullable Integer raw) {
        return raw == null || raw < 0 ? 0 : raw;
    }
}
