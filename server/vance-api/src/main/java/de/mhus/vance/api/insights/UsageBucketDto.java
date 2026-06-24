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

    /** Number of LLM round-trips that contributed. */
    private long calls;
}
