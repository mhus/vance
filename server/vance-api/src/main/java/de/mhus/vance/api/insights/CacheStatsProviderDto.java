package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prompt-cache statistics for one provider/model slice of a process.
 *
 * <p>Answers "which wire produced these cache numbers?" — the question the
 * process-wide {@link CacheStatsDto} cannot answer on its own. One entry per
 * {@code providerModel} observed in the process's LLM traces; a process that
 * ran on a fallback chain shows one entry per answering wire.
 *
 * <p>Counters follow the same rules as the process-wide ones: measured cache
 * tokens are what the provider itemized (Anthropic natively, OpenAI/Gemini
 * via the cache-aware adapter), {@link #implicitCacheReadTokens} is the
 * labeled estimate for gateways that bill a tail without itemizing, and the
 * two are never added together into one "cache read" number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class CacheStatsProviderDto {

    /**
     * {@code providerInstance:model} of the wire that answered, e.g.
     * {@code anthropic:claude-sonnet-4-5}. Falls back to the configured model
     * name when the trace row predates provider tracking.
     */
    private String providerModel;

    /** Number of OUTPUT trace rows (= LLM round-trips) for this wire. */
    private long roundTrips;

    /** Total uncached input tokens. */
    private long inputTokens;

    /** Total output tokens. */
    private long outputTokens;

    /** Total tokens written to the prompt cache. */
    private long cacheCreationInputTokens;

    /** Total tokens read from the prompt cache (provider-measured). */
    private long cacheReadInputTokens;

    /** Estimated cache reads the provider never itemized — labeled, no cost. */
    private long implicitCacheReadTokens;

    /**
     * Measured cache-hit rate as a fraction in [0.0, 1.0]; same formula as
     * {@link CacheStatsDto#getHitRate()}.
     */
    private double hitRate;

    /**
     * Smallest prompt this model family will cache at all (tokens) —
     * {@code null} when the catalog has no entry for the wire. Prompts below
     * this produce zero cache counters without anything being wrong.
     */
    private Integer minCacheableInputTokens;
}
