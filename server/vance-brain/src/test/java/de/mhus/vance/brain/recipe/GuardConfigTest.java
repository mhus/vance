package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit coverage of the two {@link GuardConfig} shapes' validation. */
class GuardConfigTest {

    // ───────────────────────── ScriptGuard ─────────────────────────

    @Test
    void scriptPath_populatesPathShape() {
        ScriptGuard g = ScriptGuard.ofPath("_vance/guards/x.js", true, GuardPoint.STOP, 3);
        assertThat(g.scriptPath()).isEqualTo("_vance/guards/x.js");
        assertThat(g.scriptBody()).isNull();
        assertThat(g.allowTools()).isTrue();
        assertThat(g.params()).isEmpty();
    }

    @Test
    void scriptPath_withParams_carriesParams() {
        ScriptGuard g = ScriptGuard.ofPath(
                "_vance/guards/llm-judge.js", Map.of("judge", "done?", "prompt", "do it"), false, GuardPoint.STOP, 2);
        assertThat(g.params()).containsEntry("judge", "done?").containsEntry("prompt", "do it");
    }

    @Test
    void scriptBody_populatesInlineShape() {
        ScriptGuard g = ScriptGuard.ofBody("vance.guard.continueWith('x');", false, GuardPoint.BOTH, 1);
        assertThat(g.scriptBody()).isEqualTo("vance.guard.continueWith('x');");
        assertThat(g.scriptPath()).isNull();
    }

    @Test
    void script_bothSources_rejected() {
        assertThatThrownBy(() -> new ScriptGuard("path.js", "body", Map.of(), false, GuardPoint.STOP, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void script_noSource_rejected() {
        assertThatThrownBy(() -> new ScriptGuard(null, null, Map.of(), false, GuardPoint.STOP, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void script_negativeMaxRounds_rejected() {
        assertThatThrownBy(() -> ScriptGuard.ofPath("x.js", false, GuardPoint.STOP, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void script_firesOnlyAtItsPoint() {
        ScriptGuard stop = ScriptGuard.ofPath("x.js", false, GuardPoint.STOP, 2);
        assertThat(stop.firesOnNaturalStop()).isTrue();
        assertThat(stop.firesOnTerminate()).isFalse();
        assertThat(stop.firesOnStart()).isFalse();
        assertThat(stop.firesOnCommand()).isFalse();
    }

    // ───────────────────────── HandlerGuard ─────────────────────────

    @Test
    void handler_withoutTrigger_firesAtAllPoints() {
        HandlerGuard g = new HandlerGuard("fence-check", Map.of(), null, 2);
        assertThat(g.handlerName()).isEqualTo("fence-check");
        assertThat(g.firesOnStart()).isTrue();
        assertThat(g.firesOnCommand()).isTrue();
        assertThat(g.firesOnNaturalStop()).isTrue();
        assertThat(g.firesOnTerminate()).isTrue();
    }

    @Test
    void handler_narrowedTrigger_firesOnlyThere() {
        HandlerGuard g = new HandlerGuard("fence-check", Map.of(), GuardPoint.COMMAND, 2);
        assertThat(g.firesOnCommand()).isTrue();
        assertThat(g.firesOnNaturalStop()).isFalse();
        assertThat(g.firesOnStart()).isFalse();
    }

    @Test
    void handler_blankName_rejected() {
        assertThatThrownBy(() -> new HandlerGuard("  ", Map.of(), null, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handler_negativeMaxRounds_rejected() {
        assertThatThrownBy(() -> new HandlerGuard("x", Map.of(), null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handler_nullParams_defaultsToEmpty() {
        assertThat(new HandlerGuard("x", null, null, 2).params()).isEmpty();
    }
}
