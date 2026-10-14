package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;

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
}
