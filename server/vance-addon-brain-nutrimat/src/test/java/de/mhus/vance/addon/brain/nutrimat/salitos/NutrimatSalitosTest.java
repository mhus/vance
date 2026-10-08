package de.mhus.vance.addon.brain.nutrimat.salitos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.StopDecision;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The single axis this nature owns: the stop is a decision — a judge says
 * done or continue at every natural-stop candidate, bounded by the decision
 * budget — and every verdict goes out through the report channel.
 */
class NutrimatSalitosTest {

    private final NutrimatJudge judge = mock(NutrimatJudge.class);
    private final NotificationService notifications = mock(NotificationService.class);

    // Positional nulls on purpose — a constructor change must break compile.
    private final NutrimatSalitos engine = new NutrimatSalitos(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            notifications,
            judge);

    private static LoopState state(ThinkProcessDocument process, int stopCandidates) {
        return new LoopState(process, null, "list the modules", 4, 40, "draft", 0, 0, stopCandidates, 0, false);
    }

    private static ThinkProcessDocument processWithDecisionBudget(int maxDecisions) {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setEngineParams(Map.of("maxDecisions", maxDecisions));
        return process;
    }

    @Test
    void onNaturalStopCandidate_judgeDone_acceptsTheDraftAndPublishesTheVerdict() {
        when(judge.judgeContinue(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ContinueJudgment(true, "", "answers the request"));

        ThinkProcessDocument process = processWithDecisionBudget(3);
        StopDecision d =
                engine.onNaturalStopCandidate(state(process, 1), AiMessage.from("the modules are api, shared, brain"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.ACCEPT);
        // The verdict is published, not just applied — through the report
        // channel: recorded in the loop state, pinged to the session.
        verify(notifications).publish(process, "stop verdict: done — answers the request", NotificationSeverity.INFO);
        assertThat(process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", java.util.List.of("stop verdict: done — answers the request"));
    }

    @Test
    void onNaturalStopCandidate_judgeContinue_pushesTheLoopOnAndPublishesTheVerdict() {
        when(judge.judgeContinue(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ContinueJudgment(
                        false, "you promised the versions — read the poms", "promised work missing"));

        ThinkProcessDocument process = processWithDecisionBudget(3);
        StopDecision d = engine.onNaturalStopCandidate(state(process, 1), AiMessage.from("let me look that up"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.CONTINUE);
        assertThat(d.message()).contains("read the poms");
        // The continue verdict is published too — the decision stays pure.
        verify(notifications)
                .publish(process, "stop verdict: continue — promised work missing", NotificationSeverity.INFO);
    }

    @Test
    void onNaturalStopCandidate_decisionBudgetSpent_acceptsWithoutAsking() {
        // maxDecisions=1 and the second candidate: the loop must end on an
        // answer, never on a judge question loop.
        StopDecision d = engine.onNaturalStopCandidate(state(processWithDecisionBudget(1), 2), AiMessage.from("draft"));

        assertThat(d.kind()).isEqualTo(StopDecision.Kind.ACCEPT);
        // No judge was asked, so there is nothing to publish.
        verifyNoInteractions(judge, notifications);
    }
}
