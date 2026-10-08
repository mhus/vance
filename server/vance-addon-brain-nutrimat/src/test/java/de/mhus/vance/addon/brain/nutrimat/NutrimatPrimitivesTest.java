package de.mhus.vance.addon.brain.nutrimat;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The base's loop primitives, driven through a minimal test nature whose
 * loop is the plainest one possible: dispatch tool rounds into the working
 * log, take the first text-only message as the reply, optionally send one
 * report. Pins what every nature builds on — the working log, the report
 * channel, the narration knob, the JSON helper and the interrupt in
 * {@code round()}.
 */
class NutrimatPrimitivesTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    /** The plainest loop a nature can build from the primitives. */
    private AbstractNutrimat nature(@Nullable String reportText) {
        return new AbstractNutrimat(
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
                h.notifications) {
            @Override
            protected String natureId() {
                return "janx";
            }

            @Override
            protected String loopType() {
                return "primitives test loop";
            }

            @Override
            protected TurnOutcome runLoop(
                    ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
                for (int iter = 0; ; iter++) {
                    narrateRound(ctx, process, "round " + (iter + 1));
                    AiMessage reply = round(process, ctx, in);
                    String t = reply.text() == null ? "" : reply.text();
                    if (!reply.hasToolExecutionRequests()) {
                        if (reportText != null) report(process, reportText);
                        narrate(ctx, process, "stop: accepted");
                        return TurnOutcome.terminal(t, false);
                    }
                    appendInterimRoundText(ctx, process, t);
                    dispatchTools(process, in, reply);
                }
            }
        };
    }

    private TurnOutcome run(@Nullable String reportText) {
        return nature(reportText).runLoop(h.process, h.ctx, h.inputs(), new LoopStats());
    }

    @Test
    void toolRound_persistsWorkingLog_theFinalRoundNeverDoes() {
        h.script(toolCall("{}", "round one text"), text("the final answer"));

        TurnOutcome outcome = run(null);

        assertThat(outcome.finalText()).isEqualTo("the final answer");
        // The tool round's text lands in the working log once, as interim;
        // the reply is the shell's to commit, never the loop's.
        List<ChatMessageDocument> roundTexts = h.appended.stream()
                .filter(d -> "round one text".equals(d.getContent()))
                .toList();
        assertThat(roundTexts).hasSize(1);
        assertThat(roundTexts.get(0).isInterim()).isTrue();
        assertThat(h.contents()).doesNotContain("the final answer");
    }

    @Test
    void dispatchTools_appendsReplyAndResults() {
        h.script(toolCall("{}", ""), text("done"));
        LoopInputs in = h.inputs();

        nature(null).runLoop(h.process, h.ctx, in, new LoopStats());

        // user message + assistant tool-call message + tool result
        assertThat(in.messages()).hasSize(3);
        verify(h.tools).invoke(anyString(), any());
    }

    @Test
    void round_interruptsBeforeTheModelCall() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(() -> run(null)).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
        // The halt flag is cleared on the way out, the shell parks PAUSED.
        verify(h.thinkProcessService).clearHalt("p1");
    }

    @Test
    void round_statusFlipInterruptsWithoutForcePause() {
        h.script(text("never reached"));
        h.process.setStatus(de.mhus.vance.api.thinkprocess.ThinkProcessStatus.SUSPENDED);

        assertThatThrownBy(() -> run(null))
                .isInstanceOf(NutrimatInterruptedException.class)
                .satisfies(e -> assertThat(((NutrimatInterruptedException) e).forcePause())
                        .isFalse());
    }

    @Test
    void report_recordsIntoThePersistedLoopState() {
        h.script(text("the final answer"));

        run("I read the pom.xml and listed the modules");

        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("I read the pom.xml and listed the modules"));
    }

    @Test
    void report_publishesTheReportToTheSession() {
        h.script(text("the final answer"));

        run("I read the pom.xml and listed the modules");

        verify(h.notifications)
                .publish(h.process, "I read the pom.xml and listed the modules", NotificationSeverity.INFO);
    }

    @Test
    void report_truncatesLongReportsForThePing() {
        h.script(text("final answer"));

        run("x".repeat(200));

        // A short ping (≤120 chars); the recorded report keeps the full text.
        verify(h.notifications).publish(h.process, "x".repeat(120) + "…", NotificationSeverity.INFO);
    }

    @Test
    void report_hiddenProcessRecordsButStaysSilent() {
        h.process.setHiddenFromUi(true);
        h.script(text("final answer"));

        run("I worked silently");

        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("I worked silently"));
        verify(h.notifications, never()).publish(any(), anyString(), any());
    }

    @Test
    void narration_defaultModeShowsRoundsAndDecisions() {
        h.script(text("the final answer"));

        run(null);

        assertThat(h.contents()).contains("[janx] round 1", "[janx] stop: accepted");
    }

    @Test
    void narration_roundsMode_suppressesDecisionsButKeepsRoundNotes() {
        h.process.getEngineParams().put("loopNarration", "rounds");
        h.script(text("the final answer"));

        run(null);

        assertThat(h.contents()).contains("[janx] round 1");
        assertThat(h.contents()).noneMatch(c -> c.contains("stop: accepted"));
    }

    @Test
    void narration_offMode_emitsNoNotes_theWorkingLogStays() {
        h.process.getEngineParams().put("loopNarration", "off");
        h.script(toolCall("{}", "round one text"), text("the final answer"));

        run(null);

        assertThat(h.contents()).noneMatch(c -> c.startsWith("[janx]"));
        assertThat(h.contents()).contains("round one text");
    }

    @Test
    void jsonObjectOf_toleratesFencesAndProse_rejectsNonObjects() {
        AbstractNutrimat n = nature(null);

        assertThat(n.jsonObjectOf("{\"done\": true}")).containsEntry("done", true);
        assertThat(n.jsonObjectOf("Here you go:\n```json\n{\"report\": \"did it\"}\n```"))
                .containsEntry("report", "did it");
        assertThat(n.jsonObjectOf("no json at all")).isNull();
        assertThat(n.jsonObjectOf("{broken")).isNull();
        assertThat(n.jsonObjectOf(null)).isNull();
    }
}
