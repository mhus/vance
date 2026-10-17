package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.power.LlmPowerGate;
import de.mhus.vance.shared.llmusage.CallAttribution;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link UsageGate} every model decorator receives: the power switch
 * first, then the cost budget.
 *
 * <p>The order is part of the contract. A switched-off tenant or user is
 * refused before the budget is consulted, so the refusal names the switch
 * rather than a budget, and the budget's aggregation is not run for a call
 * that cannot go out anyway.
 *
 * <p>{@link Primary} because both gates are beans of type {@link UsageGate}
 * too; injection points that ask for "the gate" get this chain.
 */
@Component
@Primary
@RequiredArgsConstructor
public class UsageGateChain implements UsageGate {

    private final LlmPowerGate powerGate;
    private final LlmBudgetGate budgetGate;

    @Override
    public void admit(CallAttribution attribution, ModelInfo model) {
        powerGate.admit(attribution, model);
        budgetGate.admit(attribution, model);
    }
}
