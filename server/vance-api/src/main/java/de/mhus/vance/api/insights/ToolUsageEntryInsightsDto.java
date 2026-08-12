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
 * Demand counters for one tool within one role — the row level of the
 * tool-usage insights tab.
 *
 * <p>{@code calls} are successful invocations (the delegated leg of a
 * wrapper call is not counted separately), {@code discoveryHits} are
 * {@code tool_description} lookups. The second number matters because it
 * measures demand <em>before</em> the deferral hurdle: a tool the budget
 * demoted is harder to reach, so calls alone would keep it demoted
 * forever. See {@code specification/public/server-tools.md} §14.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ToolUsageEntryInsightsDto {

    private String toolName;

    /** Name-derived family — the unit the budget demotes as a whole. */
    private @Nullable String family;

    private long calls;

    private long discoveryHits;

    /** {@code calls + discoveryHits} — the number the ranking uses. */
    private long demand;

    private @Nullable Instant lastCallAt;

    private @Nullable Instant lastDiscoveryAt;
}
