package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resource-lease view of one tenant: the held leases and the funnel
 * counters that count only this tenant. The global level sums up every
 * tenant and is deliberately absent — see
 * {@code specification/public/lease-system.md} §9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LeaseInsightsDto {

    /** Held (and still acquiring) leases of the tenant. */
    @Builder.Default
    private List<LeaseHolderInsightsDto> holders = new ArrayList<>();

    /** Tenant, project and user counters of the tenant. */
    @Builder.Default
    private List<LeaseCounterInsightsDto> counters = new ArrayList<>();
}
