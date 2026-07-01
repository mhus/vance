package de.mhus.vance.brain.tools.builtins;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FindToolsTool#filterMatches} — the pure
 * visibility/scoping step. The key behaviour (added 2026-07-01): when an
 * engine allow-set is known, {@code find_tools} must not advertise tools
 * the engine can't invoke.
 */
class FindToolsToolTest {

    private static ToolSpec spec(String name, boolean primary) {
        return ToolSpec.builder().name(name).description(name + " does things").primary(primary).build();
    }

    private final List<ToolSpec> all = List.of(
            spec("doc_read", false),
            spec("workpage_create", false),
            spec("respond", true));

    @Test
    void emptyInvocable_meansNoScopeFilter() {
        List<Map<String, Object>> out = FindToolsTool.filterMatches(all, null, false, Set.of());

        assertThat(out).extracting(m -> m.get("name"))
                .containsExactlyInAnyOrder("doc_read", "workpage_create");
    }

    @Test
    void nonEmptyInvocable_hidesToolsOutsideTheAllowSet() {
        // workpage_create is dispatchable in the context but NOT invocable
        // by this engine — it must not be advertised.
        List<Map<String, Object>> out =
                FindToolsTool.filterMatches(all, null, false, Set.of("doc_read"));

        assertThat(out).extracting(m -> m.get("name")).containsExactly("doc_read");
    }

    @Test
    void primaryToolsExcludedByDefault_includedWhenRequested() {
        assertThat(FindToolsTool.filterMatches(all, null, false, Set.of()))
                .extracting(m -> m.get("name")).doesNotContain("respond");

        assertThat(FindToolsTool.filterMatches(all, null, true, Set.of()))
                .extracting(m -> m.get("name")).contains("respond");
    }

    @Test
    void queryFiltersByNameOrDescription_caseInsensitive() {
        List<Map<String, Object>> out =
                FindToolsTool.filterMatches(all, "WORKPAGE", false, Set.of());

        assertThat(out).extracting(m -> m.get("name")).containsExactly("workpage_create");
    }

    @Test
    void queryAndScopeApplyTogether() {
        // Query matches workpage_create, but it's outside the allow-set → empty.
        List<Map<String, Object>> out =
                FindToolsTool.filterMatches(all, "workpage", false, Set.of("doc_read"));

        assertThat(out).isEmpty();
    }
}
