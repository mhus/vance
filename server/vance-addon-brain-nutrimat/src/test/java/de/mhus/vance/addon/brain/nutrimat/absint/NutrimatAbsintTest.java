package de.mhus.vance.addon.brain.nutrimat.absint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.StopDecision;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

/**
 * The single axis this nature owns: the natural stop is accepted (no
 * done/continue verdict), but only together with a non-empty round report
 * sent through the report channel — the base records it into the loop state
 * and pushes it to the client as a notification.
 */
class NutrimatAbsintTest {

    private final NutrimatJudge judge = mock(NutrimatJudge.class);
    private final NotificationService notifications = mock(NotificationService.class);

    // Positional nulls on purpose — a constructor change must break compile.
    private final NutrimatAbsint engine = new NutrimatAbsint(
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

    private static LoopState state(ThinkProcessDocument process) {
        return new LoopState(process, null, "list the modules", 4, 40, "draft", 0, 0, 1, 0, false);
    }

    @Test
    void onNaturalStopCandidate_acceptsWithTheRoundReportSent() {
        when(judge.reportRound(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.RoundReport("I read the pom.xml and listed the modules."));

        ThinkProcessDocument process = new ThinkProcessDocument();
        StopDecision d =
                engine.onNaturalStopCandidate(state(process), AiMessage.from("the modules are api, shared, brain"));

        // No decision — the stop is accepted; the account went out through
        // the report channel (recorded + notified), not through the decision.
        assertThat(d.kind()).isEqualTo(StopDecision.Kind.ACCEPT);
        assertThat(d.message()).isNull();
        verify(notifications).publish(process, "I read the pom.xml and listed the modules.", NotificationSeverity.INFO);
        assertThat(process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", java.util.List.of("I read the pom.xml and listed the modules."));
    }

    @Test
    void onNaturalStopCandidate_passesTheDraftToTheJudge() {
        when(judge.reportRound(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.RoundReport("account"));

        engine.onNaturalStopCandidate(
                state(new ThinkProcessDocument()), AiMessage.from("the modules are api, shared, brain"));

        // The judge sees the model's draft text and the loop position — the
        // account is grounded in what the round actually said.
        verify(judge).reportRound(any(), anyString(), eq("the modules are api, shared, brain"), anyInt());
    }

    @Test
    void roundNarration_carriesTheModelsOwnWords() {
        LoopState state = new LoopState(null, null, "goal", 11, 40, "", 0, 0, 0, 0, false);

        assertThat(engine.roundNarration(state, "TypeScript 7 removed baseUrl. I need to use relative paths instead."))
                .isEqualTo("round 12: TypeScript 7 removed baseUrl. I need to use relative paths instead.");
    }

    @Test
    void roundNarration_textlessRoundFallsBackToThePlainCounter() {
        LoopState state = new LoopState(null, null, "goal", 4, 40, "", 0, 0, 0, 0, false);

        assertThat(engine.roundNarration(state, "")).isEqualTo("round 5");
        assertThat(engine.roundNarration(state, null)).isEqualTo("round 5");
    }

    @Test
    void roundNarration_longTextIsTruncated() {
        LoopState state = new LoopState(null, null, "goal", 4, 40, "", 0, 0, 0, 0, false);

        // The note is a dimmed progress line; the full text lives in the working log.
        assertThat(engine.roundNarration(state, "x".repeat(200))).isEqualTo("round 5: " + "x".repeat(160) + "…");
    }
}
