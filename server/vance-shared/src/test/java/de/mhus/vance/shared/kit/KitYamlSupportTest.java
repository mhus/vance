package de.mhus.vance.shared.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reading primitives every kit file shares. */
class KitYamlSupportTest {

    @Test
    void loadMap_emptyTextIsAnEmptyMap() {
        assertThat(KitYamlSupport.loadMap("", "x.yaml")).isEmpty();
    }

    @Test
    void loadMap_aTopLevelListIsRefused() {
        assertThatThrownBy(() -> KitYamlSupport.loadMap("- a\n", "x.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("x.yaml must be a top-level map");
    }

    @Test
    void loadMap_duplicateKeys_lastWinsByDefault_andAreRefusedOnRequest() {
        assertThat(KitYamlSupport.loadMap("a: 1\na: 2\n", "x.yaml")).containsEntry("a", 2);
        assertThatThrownBy(() -> KitYamlSupport.loadMap("a: 1\na: 2\n", "x.yaml", false))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("Failed to parse x.yaml");
    }

    @Test
    void requireString_missingAndBlankAreBothRefused() {
        assertThatThrownBy(() -> KitYamlSupport.requireString(Map.of(), "name", "kit.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("missing required field 'name'");
        assertThatThrownBy(() -> KitYamlSupport.requireString(Map.of("name", "  "), "name", "kit.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void stringOrNull_trimsAndTreatsBlankAsAbsent() {
        assertThat(KitYamlSupport.stringOrNull(" a ")).isEqualTo("a");
        assertThat(KitYamlSupport.stringOrNull(" ")).isNull();
        assertThat(KitYamlSupport.stringOrNull(null)).isNull();
    }

    @Test
    void booleanOr_readsBooleansAndText() {
        assertThat(KitYamlSupport.booleanOr(null, true)).isTrue();
        assertThat(KitYamlSupport.booleanOr("TRUE", false)).isTrue();
        assertThat(KitYamlSupport.booleanOrFalse(Boolean.TRUE)).isTrue();
        assertThat(KitYamlSupport.booleanOrFalse("nope")).isFalse();
    }

    @Test
    void parseInstant_acceptsTextAndDate_andIgnoresGarbage() {
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        assertThat(KitYamlSupport.parseInstant("2026-01-02T03:04:05Z")).isEqualTo(at);
        assertThat(KitYamlSupport.parseInstant(java.util.Date.from(at))).isEqualTo(at);
        assertThat(KitYamlSupport.parseInstant("yesterday")).isNull();
    }
}
