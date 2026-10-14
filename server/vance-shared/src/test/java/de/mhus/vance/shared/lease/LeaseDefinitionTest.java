package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parsing of one lease-definition layer: absent fields stay {@code null}
 * (the loader's merge relies on telling "not set here" apart from "set"),
 * the resource key falls back to the definition name.
 */
class LeaseDefinitionTest {

    @Test
    void fromLayer_missingYaml_yieldsAllNullCaps() {
        LeaseDefinition def = LeaseDefinition.fromLayer("tex", null);

        assertThat(def.name()).isEqualTo("tex");
        assertThat(def.maxGlobal()).isNull();
        assertThat(def.maxTenant()).isNull();
        assertThat(def.maxProject()).isNull();
        assertThat(def.maxUser()).isNull();
        assertThat(def.ttlSeconds()).isNull();
        assertThat(def.waitMs()).isNull();
        assertThat(def.hasAnyCap()).isFalse();
    }

    @Test
    void fromLayer_fullYaml_parsesEveryField() {
        LeaseDefinition def = LeaseDefinition.fromLayer(
                "tex",
                Map.of(
                        "resource",
                        "tex2pdf",
                        "levels",
                        Map.of("global", 6, "tenant", 3, "project", 1, "user", 2),
                        "ttlSeconds",
                        90,
                        "waitMs",
                        5000));

        assertThat(def.resourceKey()).isEqualTo("tex2pdf");
        assertThat(def.maxGlobal()).isEqualTo(6L);
        assertThat(def.maxTenant()).isEqualTo(3L);
        assertThat(def.maxProject()).isEqualTo(1L);
        assertThat(def.maxUser()).isEqualTo(2L);
        assertThat(def.ttlSeconds()).isEqualTo(90L);
        assertThat(def.waitMs()).isEqualTo(5000L);
        assertThat(def.hasAnyCap()).isTrue();
    }

    @Test
    void resourceKey_fallsBackToName_whenResourceUnset() {
        LeaseDefinition def = LeaseDefinition.fromLayer("browser-session", Map.of("levels", Map.of("global", 2)));

        assertThat(def.resourceKey()).isEqualTo("browser-session");
    }

    @Test
    void defaults_applyOnlyAtAcquire_whenNoLayerSetsTheTiming() {
        LeaseDefinition def = LeaseDefinition.fromLayer("tex", Map.of());

        assertThat(def.ttlSecondsOrDefault()).isEqualTo(LeaseDefinition.DEFAULT_TTL_SECONDS);
        assertThat(def.waitMsOrDefault()).isEqualTo(LeaseDefinition.DEFAULT_WAIT_MS);
    }

    @Test
    void fromLayer_capBelowOne_isRejected() {
        // cap 0 would not block: the first acquisition creates the counter.
        assertThatThrownBy(() -> LeaseDefinition.fromLayer("tex", Map.of("levels", Map.of("project", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("levels.project")
                .hasMessageContaining("at least 1");
    }

    @Test
    void fromLayer_nonNumericCap_isAnErrorNotUnset() {
        assertThatThrownBy(() -> LeaseDefinition.fromLayer("tex", Map.of("levels", Map.of("tenant", "three"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("levels.tenant")
                .hasMessageContaining("not a number");
    }

    @Test
    void isValidName_acceptsPlainNames_rejectsPathSyntax() {
        assertThat(LeaseDefinition.isValidName("tex")).isTrue();
        assertThat(LeaseDefinition.isValidName("browser-session_2")).isTrue();
        assertThat(LeaseDefinition.isValidName("../x")).isFalse();
        assertThat(LeaseDefinition.isValidName("a/b")).isFalse();
        assertThat(LeaseDefinition.isValidName("tex.yaml")).isFalse();
        assertThat(LeaseDefinition.isValidName("")).isFalse();
        assertThat(LeaseDefinition.isValidName(null)).isFalse();
    }
}
