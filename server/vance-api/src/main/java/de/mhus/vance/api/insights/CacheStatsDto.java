package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated prompt-cache statistics for one scope (process, session, or
 * tenant). Built by walking the {@code llm_traces} collection and summing the
 * per-row counters every cache-aware wire produces: Anthropic reports them
 * natively, OpenAI and Gemini are normalized into the same counters by the
 * cache-aware usage adapter.
 *
 * <p>Drives Insights views that answer "is caching actually saving us
 * tokens?" — the {@link #hitRate} is the headline metric.
 *
 * <p>Two kinds of cache savings appear here and are kept apart on purpose:
 * measured counters ({@link #cacheCreationInputTokens},
 * {@link #cacheReadInputTokens}) and the labeled estimate for gateways that
 * bill a tail without itemizing ({@link #implicitCacheReadTokens}). They are
 * never summed into one "cache read" number; {@link #hitRateIncludingEstimate}
 * is the only place both contribute.
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
     * Estimated cache reads the provider never itemized (tail-billing
     * gateways) — informational, carries no cost, never mixed into
     * {@link #cacheReadInputTokens}. See {@code ImplicitCacheEstimator}.
     */
    private long implicitCacheReadTokens;

    /**
     * Cache-hit rate as a fraction in [0.0, 1.0]:
     * {@code cacheReadInputTokens / (inputTokens + cacheCreation + cacheRead)}.
     * {@code 0.0} when no input tokens were observed. Measured only.
     */
    private double hitRate;

    /**
     * Hit rate including the estimate, fraction in [0.0, 1.0]:
     * {@code (cacheRead + implicitCacheRead) / (input + creation + read +
     * implicitCacheRead)}. The honest figure for tail-billing gateways, where
     * the reported input is only the uncached tail — clearly labeled as
     * including an estimate wherever it is displayed.
     */
    private double hitRateIncludingEstimate;

    /**
     * Per-wire slice of these totals — one entry per {@code providerModel}
     * observed in the process. Answers "which provider produced these cache
     * numbers?"; empty when no trace row contributed.
     */
    private List<CacheStatsProviderDto> providers;
}
