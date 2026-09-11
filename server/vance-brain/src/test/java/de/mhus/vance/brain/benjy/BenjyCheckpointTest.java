package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Grant semantics of an answered checkpoint question (§6): exhaustion is a
 * decision point, not a verdict — the answer re-grants the matching budget
 * and the loop continues with route. Any answer is new input, so the
 * stagnation clock and the reflect convergence cap restart on every answer;
 * only a token checkpoint moves the budget offset (the counters stay
 * truthful for the final report).
 */
class BenjyCheckpointTest {

    private static BenjyState parkedState(String checkpoint, long tokens, long offset) {
        BenjyState state = new BenjyState();
        state.setPendingQuestion("⚠️ Benjy safety-net checkpoint: ...");
        state.setPendingCheckpoint(checkpoint);
        state.getCounters().setTokens(tokens);
        state.setTokenBudgetOffset(offset);
        state.getCounters().setNoProgressStreak(15);
        state.setStagnationEscalated(true);
        state.setReflectNoCount(3);
        return state;
    }

    @Test
    void tokenCheckpointAnswer_regrantsBudgetFromCurrentConsumption() {
        BenjyState state = parkedState("tokens", 2_000_000L, 0L);

        BenjyEngine.applyCheckpointAnswer(state);

        // A fresh full budget is granted from the current consumption …
        assertThat(state.getTokenBudgetOffset()).isEqualTo(2_000_000L);
        // … while the counters stay untouched — the final report must not
        // understate what the run really cost.
        assertThat(state.getCounters().getTokens()).isEqualTo(2_000_000L);
        assertThat(state.getPendingCheckpoint()).isNull();
    }

    @Test
    void anyAnswer_restartsStagnationClockAndConvergenceCap() {
        BenjyState state = parkedState("stagnation", 500L, 0L);

        BenjyEngine.applyCheckpointAnswer(state);

        assertThat(state.getCounters().getNoProgressStreak()).isZero();
        assertThat(state.isStagnationEscalated()).isFalse();
        assertThat(state.getReflectNoCount()).isZero();
        assertThat(state.getPendingCheckpoint()).isNull();
    }

    @Test
    void ordinaryAskParentAnswer_movesNoTokenOffset() {
        BenjyState state = parkedState(null, 500L, 0L);

        BenjyEngine.applyCheckpointAnswer(state);

        // ask_parent carries no grant semantics — cost is cost, the
        // stagnation restart is the only effect.
        assertThat(state.getTokenBudgetOffset()).isZero();
        assertThat(state.getCounters().getNoProgressStreak()).isZero();
        assertThat(state.getPendingCheckpoint()).isNull();
    }

    @Test
    void wallclockAnswer_needsNoGrant() {
        BenjyState state = parkedState("wallclock", 500L, 100L);

        BenjyEngine.applyCheckpointAnswer(state);

        // The phase restarts at the next loop entry — the offset stays
        // where it was, a previous token grant must not be lost.
        assertThat(state.getTokenBudgetOffset()).isEqualTo(100L);
    }
}
