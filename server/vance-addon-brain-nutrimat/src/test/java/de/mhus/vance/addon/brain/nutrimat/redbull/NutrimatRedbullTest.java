package de.mhus.vance.addon.brain.nutrimat.redbull;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatExhaustedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * redbull's own loop: the hard round budget is the semantics — reaching it
 * raises the exhausted error (no continuation, no rescue); an answer inside
 * the budget is an ordinary reply that leaves the process IDLE.
 */
class NutrimatRedbullTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatRedbull engine = new NutrimatRedbull(
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
    void answerWithinTheBudget_isAnOrdinaryReply() {
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
    }

    @Test
    void reachingTheBudget_raisesTheExhaustedError() {
        h.process.getEngineParams().put("maxIterations", 3);
        h.script(toolCall("{\"n\":1}", "partial progress"));

        assertThatThrownBy(this::run)
                .isInstanceOf(NutrimatExhaustedException.class)
                .hasMessageContaining("exhausted")
                .hasMessageContaining("3");
        assertThat(h.calls()).isEqualTo(3);
    }

    @Test
    void llmFailure_neverRescuesPartialWork() {
        h.script(toolCall("{\"n\":1}", "partial progress"), text("never"));
        h.failAt(2, new IllegalStateException("stream gone"));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("stream gone").doesNotContain("partial progress");
    }

    @Test
    void emptyReply_isAHardStop_neverASilentEnd() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void roundNotes_countAgainstTheBudget() {
        h.process.getEngineParams().put("maxIterations", 40);
        h.script(toolCall("{\"n\":1}", ""), text("done"));

        run();

        assertThat(h.contents()).contains("[redbull] round 1/40", "[redbull] round 2/40");
    }

    @Test
    void exhaustedStopsUntilUserInput_isTrue() {
        // Without the gate, a BLOCKED primary was re-spun by stale
        // exec_finished events — the exhausted error got buried (2026-10-04).
        assertThat(engine.exhaustedStopsUntilUserInput()).isTrue();
    }

    @Test
    void iterationBudget_isTheNatureOwnedHardLimit() {
        ThinkProcessDocument withRecipe = new ThinkProcessDocument();
        withRecipe.setEngineParams(Map.of("maxIterations", 25));

        assertThat(engine.iterationBudget(withRecipe)).isEqualTo(25);
        assertThat(engine.iterationBudget(new ThinkProcessDocument())).isEqualTo(40);
    }
}
