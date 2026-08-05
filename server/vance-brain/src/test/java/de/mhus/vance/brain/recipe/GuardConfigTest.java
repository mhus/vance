package de.mhus.vance.brain.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit coverage of {@link GuardConfig}'s script-source validation. */
class GuardConfigTest {

    @Test
    void scriptPath_populatesPathShape() {
        GuardConfig g = GuardConfig.scriptPath("_vance/guards/x.js", true, GuardTrigger.STOP, 3);
        assertThat(g.scriptPath()).isEqualTo("_vance/guards/x.js");
        assertThat(g.scriptBody()).isNull();
        assertThat(g.allowTools()).isTrue();
        assertThat(g.params()).isEmpty();
    }

    @Test
    void scriptPath_withParams_carriesParams() {
        GuardConfig g = GuardConfig.scriptPath(
                "_vance/guards/llm-judge.js", Map.of("judge", "done?", "prompt", "do it"),
                false, GuardTrigger.STOP, 2);
        assertThat(g.params()).containsEntry("judge", "done?").containsEntry("prompt", "do it");
    }

    @Test
    void scriptBody_populatesInlineShape() {
        GuardConfig g = GuardConfig.scriptBody("return;", false, GuardTrigger.BOTH, 1);
        assertThat(g.scriptBody()).isEqualTo("return;");
        assertThat(g.scriptPath()).isNull();
    }

    @Test
    void bothSources_rejected() {
        assertThatThrownBy(() -> new GuardConfig(
                "path.js", "body", Map.of(), false, GuardTrigger.STOP, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noSource_rejected() {
        assertThatThrownBy(() -> new GuardConfig(
                null, null, Map.of(), false, GuardTrigger.STOP, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeMaxRounds_rejected() {
        assertThatThrownBy(() -> GuardConfig.scriptPath("x.js", false, GuardTrigger.STOP, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
