package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a run has cost in one currency. The amounts of different currencies are never added.
 *
 * <p>It is the cost of the <em>agents</em> the run spawned (and of the runs it started below it): model calls
 * made without a process, and calls older than the usage ledger keeps, are not in it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunCostDto {

    /** ISO currency code; empty for calls of models that have no price. */
    private String currency;

    private double amount;

    /** Calls of models without a price — in the ledger, but not in {@code amount}. */
    private long unpricedCalls;
}
