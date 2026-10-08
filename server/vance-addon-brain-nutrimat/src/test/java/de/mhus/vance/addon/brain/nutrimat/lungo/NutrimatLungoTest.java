package de.mhus.vance.addon.brain.nutrimat.lungo;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.api.notification.NotificationSeverity;
import dev.langchain4j.data.message.SystemMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * lungo's own loop: absint's JSON account at the stop, plus a REPORT line on
 * every tool round — each one recorded in the loop state and notified; a
 * missing one gets a reminder, never a block.
 */
class NutrimatLungoTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatLungo engine = new NutrimatLungo(
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

    private final LoopInputs in = h.inputs();

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    @SuppressWarnings("unchecked")
    private List<String> roundReports() {
        Map<String, Object> state =
                (Map<String, Object>) h.process.getEngineParams().get("nutrimatState");
        return state == null ? List.of() : (List<String>) state.getOrDefault("roundReports", List.of());
    }

    private List<String> systemTexts() {
        return in.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(m -> ((SystemMessage) m).text())
                .toList();
    }

    @Test
    void everyToolRound_isReported_andTheFinalReportIsTheReply() {
        h.script(
                toolCall("{\"n\":1}", "REPORT: reading the pom"),
                toolCall("{\"n\":2}", "REPORT: read the pom, now reading the README"),
                text("{\"report\": \"I read pom.xml and README: the modules are api, shared, brain.\"}"));

        TurnOutcome out = run();

        String finalReport = "I read pom.xml and README: the modules are api, shared, brain.";
        assertThat(out.finalText()).isEqualTo(finalReport);
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(roundReports())
                .containsExactly(
                        "round 1: reading the pom",
                        "round 2: read the pom, now reading the README",
                        "round reports: 2 given, 0 missing",
                        finalReport);
        verify(h.notifications).publish(h.process, "round 1: reading the pom", NotificationSeverity.INFO);
    }

    @Test
    void aToolRoundWithoutReport_getsAReminder_butIsNotBlocked() {
        h.script(toolCall("{\"n\":1}", "just reading"), text("{\"report\": \"done\"}"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("done");
        assertThat(h.calls()).isEqualTo(2);
        assertThat(systemTexts()).contains(NutrimatLungo.REPORT_REMINDER);
        assertThat(roundReports()).containsExactly("round reports: 0 given, 1 missing", "done");
    }

    @Test
    void notTheProtocol_getsAFormatCorrection_thenTheRawTextIsTheReport() {
        h.script(text("prose 1"), text("prose 2"), text("prose 3"));

        TurnOutcome out = run();

        assertThat(h.calls()).isEqualTo(NutrimatLungo.MAX_FORMAT_CORRECTIONS + 1);
        assertThat(out.finalText()).isEqualTo("prose 3");
        assertThat(roundReports()).containsExactly("round reports: 0 given, 0 missing", "prose 3");
    }

    @Test
    void roundReportParsing() {
        assertThat(NutrimatLungo.roundReportOf("REPORT: did a\nand b\n\nmore text"))
                .isEqualTo("did a\nand b");
        assertThat(NutrimatLungo.roundReportOf("Preface line\nREPORT: x")).isEqualTo("x");
        assertThat(NutrimatLungo.roundReportOf("report:   lower case works")).isEqualTo("lower case works");
        assertThat(NutrimatLungo.roundReportOf("no account here")).isNull();
        assertThat(NutrimatLungo.roundReportOf("REPORT:   ")).isNull();
        assertThat(NutrimatLungo.roundReportOf(null)).isNull();
    }

    @Test
    void emptyReply_isAFailure() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(this::run)
                .isInstanceOf(NutrimatInterruptedException.class)
                .satisfies(e -> assertThat(((NutrimatInterruptedException) e).forcePause())
                        .isTrue());
        assertThat(h.calls()).isZero();
    }
}
