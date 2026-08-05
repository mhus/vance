package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardScratchApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the guard scratch store surface
 * ({@code vance.guard.loopValues} / {@code sessionValues}). Backed by a
 * plain host map so no GraalJS context is needed.
 */
class ScriptGuardScratchApiTest {

    private ScriptGuardScratchApi scratch(Map<String, Object> backing) {
        return new ScriptGuardScratchApi(backing);
    }

    @Test
    void set_then_get_roundTrips() {
        Map<String, Object> backing = new LinkedHashMap<>();
        ScriptGuardScratchApi s = scratch(backing);

        s.set("askForTestsDone", Boolean.TRUE);

        assertThat(s.get("askForTestsDone")).isEqualTo(true);
        assertThat(s.has("askForTestsDone")).isTrue();
        assertThat(backing).containsEntry("askForTestsDone", true);
    }

    @Test
    void get_missingKey_returnsNull() {
        ScriptGuardScratchApi s = scratch(new LinkedHashMap<>());
        assertThat(s.get("nope")).isNull();
        assertThat(s.has("nope")).isFalse();
    }

    @Test
    void getWholeMap_isReadOnlyCopy_mutationDoesNotLeak() {
        Map<String, Object> backing = new LinkedHashMap<>();
        backing.put("a", 1L);
        ScriptGuardScratchApi s = scratch(backing);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) s.get();
        assertThat(snapshot).containsEntry("a", 1L);

        // Mutating the snapshot must not touch the backing store — the
        // only write path is set().
        snapshot.put("a", 999L);
        assertThat(backing).containsEntry("a", 1L);
    }

    @Test
    void remove_deletesKey() {
        Map<String, Object> backing = new LinkedHashMap<>();
        backing.put("x", "y");
        ScriptGuardScratchApi s = scratch(backing);

        s.remove("x");

        assertThat(s.has("x")).isFalse();
        assertThat(backing).doesNotContainKey("x");
    }

    @Test
    void set_blankKey_throws() {
        ScriptGuardScratchApi s = scratch(new LinkedHashMap<>());
        assertThatThrownBy(() -> s.set("  ", "v"))
                .isInstanceOf(ScriptHostException.class);
    }

    @Test
    void mutationsAcrossCalls_persistInSharedBacking() {
        // Simulates the re-entrant guard loop: two separate "runs" share
        // the same host-side backing map, so a flag set in run 1 is
        // visible in run 2.
        Map<String, Object> backing = new LinkedHashMap<>();
        scratch(backing).set("asked", Boolean.TRUE);
        assertThat(scratch(backing).get("asked")).isEqualTo(true);
    }
}
