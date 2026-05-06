package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated Anthropic prompt-cache statistics for one scope (process,
 * session, or tenant). Built by walking the {@code llm_traces}
 * collection and summing the per-row counters that
 * {@code AnthropicResponseMapper} populates from the SDK's
 * {@code Usage} object.
 *
 * <p>Drives Insights views that answer "is caching actually saving us
 * tokens?" — the {@link #hitRate} is the headline metric.
 *
 * <p>All token fields default to {@code 0} when no trace row contributed,
 * so a fresh process surfaces as a clean zero-counter object instead of
 * {@code null}-fields the UI has to defend against.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class CacheStatsDto {

    /** Number of OUTPUT trace rows (= LLM round-trips) considered. */
    private long roundTrips;

    /**
     * Total uncached input tokens — what came in fresh (after the last
     * cache breakpoint). Together with {@link #cacheCreationInputTokens}
     * and {@link #cacheReadInputTokens} this gives the full input volume.
     */
    private long inputTokens;

    /** Total output tokens. */
    private long outputTokens;

    /** Total tokens written to the cache (write price ~1.25× input). */
    private long cacheCreationInputTokens;

    /** Total tokens read from the cache (read price ~10% input). */
    private long cacheReadInputTokens;

    /**
     * Cache-hit rate as a fraction in [0.0, 1.0]:
     * {@code cacheReadInputTokens / (inputTokens + cacheCreation + cacheRead)}.
     * {@code 0.0} when no input tokens were observed.
     */
    private double hitRate;
}
