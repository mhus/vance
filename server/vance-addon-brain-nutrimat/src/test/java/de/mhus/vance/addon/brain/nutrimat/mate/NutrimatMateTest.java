package de.mhus.vance.addon.brain.nutrimat.mate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.ExhaustionDecision;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import org.junit.jupiter.api.Test;

/**
 * The single axis this nature owns: at exhaustion a judge decides between
 * "fresh budget, keep going" and "synthesize the answer".
 */
class NutrimatMateTest {

    private final NutrimatJudge judge = mock(NutrimatJudge.class);

    // Positional nulls on purpose — a constructor change must break compile.
    private final NutrimatMate engine = new NutrimatMate(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, judge);

    private static LoopState state() {
        return new LoopState(null, null, "explain the project layout", 10, 10, "partial progress", 0, 0, 1, false);
    }

    @Test
    void onExhausted_judgeExtend_grantsAFreshBudget() {
        when(judge.judgeExhausted(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ExhaustedJudgment(true, "now read the pom.xml", "was about to act"));

        ExhaustionDecision d = engine.onExhausted(state());

        assertThat(d.kind()).isEqualTo(ExhaustionDecision.Kind.EXTEND);
        assertThat(d.nudge()).isEqualTo("now read the pom.xml");
        assertThat(d.reason()).isEqualTo("was about to act");
    }

    @Test
    void onExhausted_judgeSynthesize_endsTheTurnAsAnAnswer() {
        when(judge.judgeExhausted(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ExhaustedJudgment(false, "the project is a Maven reactor…", "enough"));

        ExhaustionDecision d = engine.onExhausted(state());

        assertThat(d.kind()).isEqualTo(ExhaustionDecision.Kind.SYNTHESIZE);
        assertThat(d.text()).isEqualTo("the project is a Maven reactor…");
        // NOT a hard failure: the judge vouched for the answer, so the turn
        // ends normally (worker → IDLE) instead of closing INCOMPLETE.
        assertThat(d.hardFailure()).isFalse();
    }
}
