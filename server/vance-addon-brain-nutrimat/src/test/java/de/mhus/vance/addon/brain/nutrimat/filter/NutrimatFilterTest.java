package de.mhus.vance.addon.brain.nutrimat.filter;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * filter's own loop: the working context is rebuilt every round from the
 * base, the protocol with the running notes and the previous round's
 * exchange only — it never grows; an answer is an ordinary reply that
 * leaves the process IDLE.
 */
class NutrimatFilterTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatFilter engine = new NutrimatFilter(
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

    /** Snapshot of the messages each model call saw. */
    private final List<List<ChatMessage>> requests = new ArrayList<>();

    private final LoopInputs in = h.inputs();

    @BeforeEach
    void captureRequests() {
        when(h.turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> messages = inv.getArgument(0);
            requests.add(new ArrayList<>(messages));
            return messages;
        });
    }

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    @Test
    void contextDoesNotGrow_onlyBaseNotesAndTheLastRound() {
        h.script(
                toolCall("{\"n\":1}", "NOTES: step 1"),
                toolCall("{\"n\":2}", "NOTES: step 2"),
                toolCall("{\"n\":3}", "NOTES: step 3"),
                toolCall("{\"n\":4}", "NOTES: step 4"),
                toolCall("{\"n\":5}", "NOTES: step 5"),
                text("done"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("done");
        assertThat(requests).hasSize(6);
        // base (1 user message) + protocol/notes + one assistant message + its one tool result
        assertThat(requests.get(5)).hasSize(4);
        assertThat(in.messages()).hasSize(4);
        assertThat(requests).allSatisfy(r -> assertThat(r).hasSizeLessThanOrEqualTo(4));
    }

    @Test
    void notes_areCarriedIntoTheNextRound() {
        h.script(toolCall("{\"n\":1}", "NOTES: found x=42, still open: y"), text("x is 42"));

        run();

        String protocol = ((SystemMessage) requests.get(1).get(1)).text();
        assertThat(protocol).contains("YOUR NOTES SO FAR:").contains("found x=42, still open: y");
        assertThat(((SystemMessage) requests.get(0).get(1)).text()).contains("(none yet)");
    }

    @Test
    void textlessRound_keepsThePreviousNotes() {
        h.script(toolCall("{\"n\":1}", "NOTES: keep me"), toolCall("{\"n\":2}", ""), text("done"));

        run();

        assertThat(((SystemMessage) requests.get(2).get(1)).text()).contains("keep me");
        assertThat(h.contents()).contains("[filter] notes not updated — the round carried no text");
    }

    @Test
    void answerWithoutTool_isAnOrdinaryReply() {
        h.script(text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
    }

    @Test
    void emptyReply_isAFailure_neverASilentEnd() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void optInRoundCap_endsWithTheNotes() {
        h.process.getEngineParams().put("maxIterations", 2);
        h.script(toolCall("{\"n\":1}", "NOTES: half way"));

        TurnOutcome out = run();

        assertThat(h.calls()).isEqualTo(2);
        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).isEqualTo("half way");
    }

    @Test
    void noRoundCap_byDefault() {
        assertThat(engine.iterationBudget(h.process)).isZero();
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

    @Test
    void notesOf_takesTheTextAfterTheMarker_orTheWholeText() {
        assertThat(NutrimatFilter.notesOf("thinking...\nNOTES: a=1\nb=2")).isEqualTo("a=1\nb=2");
        assertThat(NutrimatFilter.notesOf("plain progress")).isEqualTo("plain progress");
        assertThat(NutrimatFilter.notesOf("")).isNull();
        assertThat(NutrimatFilter.notesOf(null)).isNull();
    }
}
