package de.mhus.vance.brain.zaphod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.thinkengine.ThinkEngine;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link ZaphodEngine#restrictHeadTools} — the leaf-worker
 * invariant that a Zaphod head may not spawn or drive other
 * think-processes. Regressing this reopens the recursive council
 * fan-out from the 2026-07-29 Got-Talent incident, where a head with
 * {@code process_spawn} spawned the other jury members as full nested
 * councils.
 */
class ZaphodHeadToolRestrictionTest {

    @Test
    void restrictHeadTools_recipeAdjustedManifest_stripsOrchestrationFamily() {
        // Recipe made a tool adjustment → effective set is materialised
        // (non-null). The engine argument must never be consulted here.
        Set<String> effective = new LinkedHashSet<>(Set.of(
                "respond", "doc_read", "process_spawn", "process_steer",
                "cross_process_create", "hactar_run", "process_status"));

        Set<String> restricted = ZaphodEngine.restrictHeadTools(effective, null);

        assertThat(restricted)
                .contains("respond", "doc_read", "process_status")
                .doesNotContain("process_spawn", "process_steer",
                        "cross_process_create", "hactar_run");
    }

    @Test
    void restrictHeadTools_nullEffective_fallsBackToEngineDefaultThenStrips() {
        // effectiveAllowedTools() is null when the recipe made no
        // adjustment. The exclusion must still bite against the engine's
        // own default set — otherwise the head keeps process_spawn.
        ThinkEngine engine = mock(ThinkEngine.class);
        when(engine.allowedTools()).thenReturn(Set.of(
                "respond", "process_spawn", "process_stop"));

        Set<String> restricted = ZaphodEngine.restrictHeadTools(null, engine);

        assertThat(restricted)
                .contains("respond")
                .doesNotContain("process_spawn", "process_stop");
    }

    @Test
    void restrictHeadTools_readOnlyIntrospectionSurvives() {
        // Read-only process introspection cannot fan out and stays —
        // over-stripping would break head recipes that legitimately
        // glance at process state.
        Set<String> effective = Set.of(
                "process_list", "process_status", "process_history_text",
                "process_spawn");

        Set<String> restricted = ZaphodEngine.restrictHeadTools(effective, null);

        assertThat(restricted)
                .contains("process_list", "process_status", "process_history_text")
                .doesNotContain("process_spawn");
    }
}
