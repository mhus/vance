package de.mhus.vance.addon.brain.nutrimat.redbull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.ExhaustionDecision;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.NutrimatExhaustedException;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The single axis this nature owns: exhaustion is an error, and nothing
 * rescues partial work. Policy-only test — the loop kernel is the shared
 * framework's business.
 */
class NutrimatRedbullTest {

    // Positional nulls on purpose — a constructor change must break compile.
    private final NutrimatRedbull engine = new NutrimatRedbull(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);

    private static LoopState state(String bestFreeText) {
        return new LoopState(null, null, "explain the project layout", 40, 40, bestFreeText, 0, 0, 2, 0, false);
    }

    @Test
    void onExhausted_raisesTheExhaustedError() {
        // The reactivated historical shape: exhaustion is an EXCEPTION, and
        // the turn shell maps it onto a visible failure.
        assertThatThrownBy(() -> engine.onExhausted(state("partial progress")))
                .isInstanceOf(NutrimatExhaustedException.class)
                .hasMessageContaining("exhausted")
                .hasMessageContaining("40");
    }

    @Test
    void exhaustedStopsUntilUserInput_isTrue() {
        // The live finding 2026-10-04: without the gate, a BLOCKED primary was
        // re-spun by stale exec_finished events — the exhausted error got
        // buried under follow-up loops. redbull hard-stops until the user
        // speaks ("continue").
        assertThat(engine.exhaustedStopsUntilUserInput()).isTrue();
    }

    void onLlmFailure_neverRescuesPartialWork() {
        // janx would carry "partial progress" out as the reply; redbull
        // surfaces the failure verbatim instead.
        ExhaustionDecision d = engine.onLlmFailure(state("partial progress"), new RuntimeException("stream gone"));
        assertThat(d.kind()).isEqualTo(ExhaustionDecision.Kind.HARD_ERROR);
        assertThat(d.text()).contains("stream gone");
    }

    @Test
    void roundNarration_countsAgainstTheHardBudget() {
        LoopState state = new LoopState(null, null, "goal", 2, 40, "", 0, 0, 0, 0, false);

        // The budget IS the semantics of this nature — the round note counts it.
        assertThat(engine.roundNarration(state, "")).isEqualTo("round 3/40");
    }

    @Test
    void iterationBudget_isTheNatureOwnedHardLimit() {
        ThinkProcessDocument withRecipe = new ThinkProcessDocument();
        withRecipe.setEngineParams(Map.of("maxIterations", 25));

        // The budget is redbull's own property — recipe param wins, the
        // default is this nature's, not a base-class constant.
        assertThat(engine.iterationBudget(withRecipe)).isEqualTo(25);
        assertThat(engine.iterationBudget(new ThinkProcessDocument())).isEqualTo(40);
    }
}
