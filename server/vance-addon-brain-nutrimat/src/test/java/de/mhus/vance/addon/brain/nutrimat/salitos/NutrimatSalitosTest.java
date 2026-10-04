package de.mhus.vance.addon.brain.nutrimat.salitos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.StopDecision;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The single axis this nature owns: the stop is a decision — a judge says
 * done or continue at every natural-stop candidate, bounded by the decision
 * budget.
 */
class NutrimatSalitosTest {

    private final NutrimatJudge judge = mock(NutrimatJudge.class);

    // Positional nulls on purpose — a constructor change must break compile.
    private final NutrimatSalitos engine = new NutrimatSalitos(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, judge);

    private static LoopState state(ThinkProcessDocument process, int stopCandidates) {
        return new LoopState(process, null, "list the modules", 4, 40, "draft", 0, 0, stopCandidates, false);
    }

    private static ThinkProcessDocument processWithDecisionBudget(int maxDecisions) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setEngineParams(Map.of("maxDecisions", maxDecisions));
        return process;
    }

    @Test
    void onNaturalStopCandidate_judgeDone_acceptsTheDraft() {
        when(judge.judgeContinue(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ContinueJudgment(true, "", "answers the request"));

        StopDecision d = engine.onNaturalStopCandidate(
                state(processWithDecisionBudget(3), 1), AiMessage.from("the modules are api, shared, brain"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.ACCEPT);
    }

    @Test
    void onNaturalStopCandidate_judgeContinue_pushesTheLoopOn() {
        when(judge.judgeContinue(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ContinueJudgment(
                        false, "you promised the versions — read the poms", "promised work missing"));

        StopDecision d = engine.onNaturalStopCandidate(
                state(processWithDecisionBudget(3), 1), AiMessage.from("let me look that up"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.CONTINUE);
        assertThat(d.message()).contains("read the poms");
    }

    @Test
    void onNaturalStopCandidate_decisionBudgetSpent_acceptsWithoutAsking() {
        // maxDecisions=1 and the second candidate: the loop must end on an
        // answer, never on a judge question loop.
        StopDecision d = engine.onNaturalStopCandidate(state(processWithDecisionBudget(1), 2), AiMessage.from("draft"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.ACCEPT);
        verifyNoInteractions(judge);
    }
}
