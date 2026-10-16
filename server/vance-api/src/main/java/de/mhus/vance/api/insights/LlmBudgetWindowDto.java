package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Consumption against one budget limit — see {@link LlmBudgetStatusDto}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LlmBudgetWindowDto {

    /** {@code daily}, {@code monthly} or {@code total}. */
    private String window = "";

    /** First day of the window, UTC, {@code yyyy-MM-dd}, inclusive. */
    private String from = "";

    /** The limit, in the level's currency. */
    private double limit;

    /** Cost booked in the level's currency within the window. */
    private double used;

    /** {@code used / limit} in percent, not capped at 100. */
    private double percent;

    /** No room left: calls on priced models are refused. */
    private boolean exhausted;

    /** Past the warning threshold, not yet exhausted. */
    private boolean warning;

    /**
     * Calls of models without pricing in the window. Their cost is unknown
     * and not part of {@link #used} — listed so the gap is visible.
     */
    private long unpricedCalls;

    /** Cost booked in other currencies, by currency — not part of {@link #used}, never converted. */
    @Builder.Default
    private Map<String, Double> uncounted = new LinkedHashMap<>();
}
