package de.mhus.vance.brain.ai;

/**
 * Capability-marker for {@link dev.langchain4j.model.output.TokenUsage}
 * subclasses that expose prompt-cache counters. Adapters whose backend
 * reports cached input tokens (Anthropic's {@code cache_creation_input_tokens}
 * / {@code cache_read_input_tokens}, OpenAI's
 * {@code prompt_tokens_details.cached_tokens}, …) implement this on
 * their {@code TokenUsage}-subclass so the trace layer can read counters
 * uniformly via {@code usage instanceof CacheAwareTokenUsage}.
 *
 * <p>Counters are <b>additive</b> to {@code inputTokenCount}: the
 * standard {@code TokenUsage.inputTokenCount} carries uncached input,
 * cache-creation and cache-read tokens come on top. Total billable
 * input = {@code inputTokenCount + cacheCreation + cacheRead} (with
 * provider-specific multipliers — Anthropic: 1.25× write / 0.1× read).
 *
 * <p>The interface also carries the one cache number that is <i>not</i>
 * measured: {@link #implicitCacheReadTokens()} is the estimate for gateways
 * that bill a tail without itemizing (see {@link ImplicitCacheEstimator}).
 * The two kinds are never summed into one "cache read" figure — consumers
 * book and display them separately.
 */
public interface CacheAwareTokenUsage {

    /** Tokens written to the prompt cache on this call. {@code 0} when none. */
    long cacheCreationInputTokens();

    /** Tokens read from the prompt cache on this call. {@code 0} when none. */
    long cacheReadInputTokens();

    /**
     * Share of {@link #cacheCreationInputTokens()} written with the 1h TTL —
     * billed at ~2× the 5m write rate. {@code 0} when the provider reports no
     * TTL split, or wrote nothing.
     */
    default long cacheCreation1hInputTokens() {
        return 0L;
    }

    /**
     * Estimated tokens served from a cache the provider did not itemize — see
     * {@link ImplicitCacheEstimator}. {@code 0} whenever
     * {@link #cacheReadInputTokens()} is a measurement: an estimate is only
     * ever produced for usages that carry no cache counters of their own.
     */
    default long implicitCacheReadTokens() {
        return 0L;
    }
}
