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
 * Cost-budget state of one level — the tenant, or one project — as served by
 * {@code GET /brain/{tenant}/usage/budget}. A level without any limit is
 * still listed, with an empty {@link #windows} list: "unlimited" is an answer,
 * not a missing row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LlmBudgetStatusDto {

    /** {@code tenant} or {@code project}. */
    private String scope = "";

    /** The settings scope — a project name, or {@code _tenant}. */
    private String scopeId = "";

    /** Currency the limits are in; only cost booked in it counts. */
    private String currency = "";

    /** Warning threshold in percent, {@code 0} = no warnings. */
    private int warnPercent;

    /**
     * {@code true} when a total limit is set without a start date — it is
     * shown but not enforced until {@code ai.llm.budget.total-since} is set.
     */
    private boolean totalIncomplete;

    /** The windows whose limit applies today. */
    @Builder.Default
    private List<LlmBudgetWindowDto> windows = new ArrayList<>();
}
