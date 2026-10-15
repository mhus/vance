package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Occupancy of one funnel level of one resource within a tenant. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LeaseCounterInsightsDto {

    /** Resource key of the lease definition. */
    private String resource;

    /** {@code tenant}, {@code project} or {@code user}. */
    private String level;

    /** Project name or user login; the tenant id on the tenant level. */
    private String scope;

    /** Slots currently booked. */
    private long count;

    /** Slots the holders actually book — differs from {@code count} only on drift reconcile has not fixed yet. */
    private long booked;

    private @Nullable Instant updatedAt;
}
