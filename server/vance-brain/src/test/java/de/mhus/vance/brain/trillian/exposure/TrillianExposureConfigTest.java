package de.mhus.vance.brain.trillian.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The YAML shape of {@code _vance/trillian/exposure.yaml}: everything a door
 * opens on must parse, and everything malformed must fail closed.
 */
class TrillianExposureConfigTest {

    @Test
    void parsesTheFullShape() {
        TrillianExposureConfig config = TrillianExposureConfig.parse("""
                exposedName: colin
                description: >-
                  Colin, delighted to be talked to.
                allowedCounterparts:
                  - wile.coyote
                  - road.runner
                headLimit: 3
                inactivityDays: 7
                doors:
                  mcp: true
                  a2a: false
                """);

        assertThat(config.exposedName()).isEqualTo("colin");
        assertThat(config.description()).startsWith("Colin");
        assertThat(config.allowedCounterparts()).containsExactly("wile.coyote", "road.runner");
        assertThat(config.headLimit()).isEqualTo(3);
        assertThat(config.inactivityDays()).isEqualTo(7);
        assertThat(config.doors().mcp()).isTrue();
        assertThat(config.doors().a2a()).isFalse();

        assertThat(config.allows("road.runner")).isTrue();
        assertThat(config.allows("marvin")).isFalse();
    }

    @Test
    void minimalShape_leavesEverythingElseAtDefaults() {
        TrillianExposureConfig config =
                TrillianExposureConfig.parse("exposedName: colin\nallowedCounterparts: [road.runner]\n");

        assertThat(config.description()).isEmpty();
        assertThat(config.headLimit()).isZero();
        assertThat(config.inactivityDays()).isZero();
        assertThat(config.doors().mcp()).isFalse();
        assertThat(config.doors().a2a()).isFalse();
    }

    @Test
    void withoutExposedName_isNotParseable() {
        assertThatThrownBy(() -> TrillianExposureConfig.parse("description: nobody"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exposedName");
    }

    @Test
    void emptyOrScalar_isNotParseable() {
        assertThatThrownBy(() -> TrillianExposureConfig.parse("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TrillianExposureConfig.parse("just-a-string"))
                .isInstanceOf(IllegalStateException.class);
    }
}
