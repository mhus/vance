package de.mhus.vance.brain.ai;

import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * A usage that carries an <i>estimated</i> cache saving and nothing else.
 *
 * <p>Produced once per attempt in the cache-aware normalization layer
 * ({@link CacheAwareUsageChatModel}) for backends that serve a prefix from
 * their own cache and bill only the tail without itemizing cache counters —
 * the detection is {@link ImplicitCacheEstimator}'s. Wrapping the estimate
 * into the usage is what keeps it out of a second computation: accounting,
 * the trace recorder and the stats loggers all read the same number off the
 * same object, which travels in the response metadata like every other usage
 * counter.
 *
 * <p>Deliberately implements {@link CacheAwareTokenUsage} with zero measured
 * counters instead of a separate interface: every consumer reads cache
 * numbers through that one interface already, and the measured/estimated
 * distinction lives in the field names, not in the type system. The estimator
 * refuses to estimate on top of a {@code CacheAwareTokenUsage}, so a wrapped
 * usage is never estimated twice.
 */
final class ImplicitCacheTokenUsage extends TokenUsage implements CacheAwareTokenUsage {

    private final long implicitCacheReadTokens;

    private ImplicitCacheTokenUsage(int inputTokens, int outputTokens, long implicitCacheReadTokens) {
        super(inputTokens, outputTokens, inputTokens + outputTokens);
        this.implicitCacheReadTokens = implicitCacheReadTokens;
    }

    /**
     * The given usage with the estimate attached. Passes {@code usage}
     * through unchanged — identity-preserving — when there is nothing to
     * attach, so callers can detect "no change" by {@code ==}.
     */
    static @Nullable TokenUsage wrap(@Nullable TokenUsage usage, long implicitCacheReadTokens) {
        if (usage == null || implicitCacheReadTokens <= 0) {
            return usage;
        }
        if (usage instanceof ImplicitCacheTokenUsage) {
            return usage;
        }
        Integer in = usage.inputTokenCount();
        Integer out = usage.outputTokenCount();
        return new ImplicitCacheTokenUsage(
                in == null || in < 0 ? 0 : in, out == null || out < 0 ? 0 : out, implicitCacheReadTokens);
    }

    @Override
    public long cacheCreationInputTokens() {
        return 0L;
    }

    @Override
    public long cacheReadInputTokens() {
        return 0L;
    }

    @Override
    public long implicitCacheReadTokens() {
        return implicitCacheReadTokens;
    }
}
