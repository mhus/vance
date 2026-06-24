package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response wrapper for {@code /brain/{tenant}/usage/*} endpoints.
 * Carries the requested window, the bucket type, and the list of
 * aggregated rows. Multiple currencies in the same window produce
 * multiple rows per bucket — one per currency — so the UI can render
 * them as separate series.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class UsageReportDto {

    /** Inclusive lower bound of the window. */
    private @Nullable Instant from;

    /** Exclusive upper bound of the window. */
    private @Nullable Instant to;

    /**
     * How rows are bucketed. {@code day} / {@code week} / {@code month}
     * for time series; {@code project} / {@code model} for top-N.
     */
    private String bucketBy = "";

    /** Aggregated rows. May be empty when the window has no usage. */
    private List<UsageBucketDto> buckets = List.of();
}
