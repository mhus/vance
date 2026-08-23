package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One bucket of aggregated LLM usage — either a time bucket (day /
 * week / month) for time-series reports, or a key bucket (project,
 * model) for top-N reports.
 *
 * <p>{@link #bucketStart} is non-null for time buckets and the
 * inclusive start of the period; {@link #key} is non-null for key
 * buckets ({@code projectId}, {@code providerModel}). Exactly one of
 * the two should be set per row but the DTO is permissive — clients
 * read whichever applies to the response.
 *
 * <p>Cost is the sum across all rate-snapshot rows in the bucket; it
 * is denominated in {@link #currency}. When a bucket mixes currencies
 * (e.g. a tenant uses both Cortecs/EUR and Anthropic/USD), this DTO
 * is emitted once per currency by the report service — the report
 * UI shows them as separate series.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class UsageBucketDto {

    /** Time-bucket start (inclusive), or {@code null} for non-time buckets. */
    private @Nullable Instant bucketStart;

    /** Key — {@code projectId} or {@code providerModel} — or {@code null} for time buckets. */
    private @Nullable String key;

    /** Currency the {@link #cost*} fields are denominated in. */
    private String currency = "";

    private long tokensIn;
    private long tokensOut;
    private long cacheReadTokens;
    private long cacheWriteTokens;

    private double costInput;
    private double costOutput;
    private double costCacheRead;
    private double costCacheWrite;
    private double costTotal;

    /** Number of successful model calls that contributed. */
    private long calls;

    /**
     * Attempts that raised — retries, chain advances, aborted streams.
     * Kept out of {@link #calls} and out of every {@code cost*} field:
     * they happened and may well have been billed, but folding them into
     * the amount would make the figure worse, not better.
     */
    private long callsFailed;

    /** Tokens burned by those failed attempts, as far as the provider said. */
    private long tokensInFailed;

    private long tokensOutFailed;

    /**
     * Calls in this bucket whose model has no price in the catalog at all.
     *
     * <p>This is what keeps the amount honest. Without it the report shows
     * a sum that looks complete while silently omitting every model that
     * lacks a {@code pricing:} block — and models do lack one. A local
     * model that genuinely costs nothing declares an explicit zero rate and
     * is therefore <i>not</i> counted here.
     */
    private long unpricedCalls;

    private long unpricedTokensIn;

    private long unpricedTokensOut;

    /**
     * Calls whose provider reported no token counts at all.
     *
     * <p>One step past {@link #unpricedCalls}: there the tokens are known and
     * the rate is missing, here neither is known. They used not to be recorded
     * at all, which made an endpoint without usage reporting vanish from the
     * report — and an absent row reads as "nothing ran".
     */
    private long unmeasuredCalls;

    /** Generated images; only non-zero for image usage. */
    private long images;
}
