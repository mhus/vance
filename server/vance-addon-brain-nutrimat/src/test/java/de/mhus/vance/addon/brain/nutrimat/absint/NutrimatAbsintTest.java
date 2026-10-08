package de.mhus.vance.addon.brain.nutrimat.absint;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.api.notification.NotificationSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * absint's own loop: every stop is an account the model gives itself — a
 * JSON {report} that is the reply, recorded in the loop state and notified.
 */
class NutrimatAbsintTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatAbsint engine = new NutrimatAbsint(
            h.thinkProcessService,
            h.objectMapper,
            h.streamingProperties,
            null,
            h.llmCallTracker,
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
            h.turnContextHandlers,
            null,
            h.notifications);

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, h.inputs(), new LoopStats());
    }

    @Test
    void report_isTheReply_recordedAndNotified() {
        h.script(
                toolCall("{\"path\":\"pom.xml\"}", "reading the pom"),
                text("{\"report\": \"I read pom.xml: the modules are api, shared, brain.\"}"));

        TurnOutcome out = run();

        String report = "I read pom.xml: the modules are api, shared, brain.";
        assertThat(out.finalText()).isEqualTo(report);
        assertThat(out.awaitingUserInput()).isFalse();
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of(report));
        verify(h.notifications).publish(h.process, report, NotificationSeverity.INFO);
    }

    @Test
    void notTheProtocol_getsAFormatCorrection_thenTheRawTextIsTheReport() {
        h.script(text("prose 1"), text("prose 2"), text("prose 3"));

        TurnOutcome out = run();

        assertThat(h.calls()).isEqualTo(NutrimatAbsint.MAX_FORMAT_CORRECTIONS + 1);
        assertThat(out.finalText()).isEqualTo("prose 3");
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("prose 3"));
    }

    @Test
    void blankReportField_isAFormatError() {
        h.script(text("{\"report\": \"  \"}"), text("{\"report\": \"did it\"}"));

        assertThat(run().finalText()).isEqualTo("did it");
    }

    @Test
    void roundNote_carriesTheModelsOwnWords() {
        assertThat(NutrimatAbsint.roundNote(11, "TypeScript 7 removed baseUrl. I need to fix the tsconfig."))
                .isEqualTo("round 12: TypeScript 7 removed baseUrl. I need to fix the tsconfig.");
        assertThat(NutrimatAbsint.roundNote(4, "")).isEqualTo("round 5");
        assertThat(NutrimatAbsint.roundNote(4, null)).isEqualTo("round 5");
        assertThat(NutrimatAbsint.roundNote(4, "x".repeat(200))).isEqualTo("round 5: " + "x".repeat(160) + "…");
    }

    @Test
    void emptyReply_isAFailure() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }
}
