package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mhus.vance.brain.ai.power.LlmPowerGate;
import de.mhus.vance.brain.ai.power.LlmPowerOffException;
import de.mhus.vance.shared.llmusage.CallAttribution;
import org.junit.jupiter.api.Test;

/** {@link UsageGateChain}: the power switch is asked first, the budget only for calls it lets through. */
class UsageGateChainTest {

    private static final CallAttribution CALL = new CallAttribution("acme", "p1", "sess-1", "proc-1", "arthur", null);

    private final LlmPowerGate powerGate = mock(LlmPowerGate.class);
    private final LlmBudgetGate budgetGate = mock(LlmBudgetGate.class);
    private final UsageGateChain chain = new UsageGateChain(powerGate, budgetGate);
    private final ModelInfo model = mock(ModelInfo.class);

    @Test
    void powerRefusal_skipsTheBudget() {
        doThrow(new LlmPowerOffException("off")).when(powerGate).admit(any(), any());

        assertThatThrownBy(() -> chain.admit(CALL, model)).isInstanceOf(LlmPowerOffException.class);
        verifyNoInteractions(budgetGate);
    }

    @Test
    void admittedByPower_isCheckedAgainstTheBudget() {
        chain.admit(CALL, model);

        verify(budgetGate).admit(CALL, model);
    }
}
