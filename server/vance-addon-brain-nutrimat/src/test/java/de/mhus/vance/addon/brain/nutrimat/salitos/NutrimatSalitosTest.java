package de.mhus.vance.addon.brain.nutrimat.salitos;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * salitos' own loop: the stop is a decision the model makes itself — a JSON
 * {done, reason, answer} at every stop. Endless by design: no round or
 * decision cap. Every decision is published through the report channel.
 */
class NutrimatSalitosTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatSalitos engine = new NutrimatSalitos(
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

    private static AiMessage decision(boolean done, String reason, String answer) {
        return text("{\"done\": " + done + ", \"reason\": \"" + reason + "\", \"answer\": \"" + answer + "\"}");
    }

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, h.inputs(), new LoopStats());
    }

    @Test
    void protocol_isATurnLocalSystemInstruction() {
        h.script(decision(true, "complete", "the answer"));
        LoopInputs in = h.inputs();

        engine.runLoop(h.process, h.ctx, in, new LoopStats());

        assertThat(in.messages())
                .anyMatch(m -> m instanceof SystemMessage s && s.text().equals(NutrimatSalitos.PROTOCOL));
    }

    @Test
    void done_endsTheTurn_theAnswerIsTheReply() {
        h.script(toolCall("{\"n\":1}", ""), decision(true, "all modules listed", "api, shared, brain"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("api, shared, brain");
        assertThat(out.awaitingUserInput()).isFalse();
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("decision: done — all modules listed"));
    }

    @Test
    void notDone_keepsTheLoopGoing_withTheModelsOwnReason() {
        h.script(
                decision(false, "the brain module is missing", ""),
                toolCall("{\"n\":1}", ""),
                decision(true, "complete now", "api, shared, brain"));
        LoopInputs in = h.inputs();

        TurnOutcome out = engine.runLoop(h.process, h.ctx, in, new LoopStats());

        assertThat(out.finalText()).isEqualTo("api, shared, brain");
        assertThat(in.messages())
                .anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage u
                        && u.singleText().contains("the brain module is missing"));
    }

    @Test
    void endlessByDesign_noDecisionCap() {
        AiMessage[] script = new AiMessage[21];
        for (int i = 0; i < 20; i++) script[i] = decision(false, "not yet " + i, "");
        script[20] = decision(true, "finally", "the answer");
        h.script(script);

        assertThat(run().finalText()).isEqualTo("the answer");
        assertThat(h.calls()).isEqualTo(21);
    }

    @Test
    void notTheProtocol_getsAFormatCorrection_thenTheRawTextIsAccepted() {
        h.script(text("just prose"), text("still prose"), text("prose again"));

        TurnOutcome out = run();

        assertThat(h.calls()).isEqualTo(NutrimatSalitos.MAX_FORMAT_CORRECTIONS + 1);
        assertThat(out.finalText()).isEqualTo("prose again");
    }

    @Test
    void doneWithoutAnAnswer_isAFormatError() {
        h.script(decision(true, "done", ""), decision(true, "done", "here it is"));

        assertThat(run().finalText()).isEqualTo("here it is");
        assertThat(h.calls()).isEqualTo(2);
    }

    @Test
    void fencedJson_isAccepted() {
        h.script(text("```json\n{\"done\": true, \"reason\": \"ok\", \"answer\": \"42\"}\n```"));

        assertThat(run().finalText()).isEqualTo("42");
    }

    @Test
    void emptyReply_isAFailure() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }
}
