package de.mhus.vance.brain.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CatalogFilter}. The filter drops catalog
 * entries whose {@code requires-tools} aren't satisfied by the
 * calling engine's allow-set. Tool entries trivially "require"
 * themselves so they vanish too.
 */
class CatalogFilterTest {

    @Test
    void empty_allowSet_passes_full_catalog_through() {
        CatalogSnapshot snap = snapshot(
                "## Tools\n\n### foo\n\nfoo desc\n\n### bar\n\nbar desc\n",
                Map.of(
                        "foo", spec("tool", Set.of("foo")),
                        "bar", spec("tool", Set.of("bar"))));

        assertThat(CatalogFilter.filter(snap, null)).isEqualTo(snap.markdown());
        assertThat(CatalogFilter.filter(snap, Set.of())).isEqualTo(snap.markdown());
    }

    @Test
    void tool_entry_dropped_when_not_in_allow_set() {
        CatalogSnapshot snap = snapshot(
                "## Tools\n\n### foo\n\nfoo desc\n\n### bar\n\nbar desc\n",
                Map.of(
                        "foo", spec("tool", Set.of("foo")),
                        "bar", spec("tool", Set.of("bar"))));

        String filtered = CatalogFilter.filter(snap, Set.of("foo"));

        assertThat(filtered).contains("### foo");
        assertThat(filtered).doesNotContain("### bar");
        assertThat(filtered).doesNotContain("bar desc");
    }

    @Test
    void manual_with_requires_tools_dropped_when_any_missing() {
        CatalogSnapshot snap = snapshot(
                "## Manuals\n\n### m1\n\nm1 desc\n\n### m2\n\nm2 desc\n",
                Map.of(
                        "m1", spec("manual", Set.of()),                  // no requirements
                        "m2", spec("manual", Set.of("research_investigate"))));

        String filtered = CatalogFilter.filter(snap, Set.of("file_read", "file_write"));

        assertThat(filtered).contains("### m1");
        assertThat(filtered).doesNotContain("### m2");
    }

    @Test
    void manual_with_all_required_tools_kept() {
        CatalogSnapshot snap = snapshot(
                "## Manuals\n\n### m1\n\nm1 desc\n\n### m2\n\nm2 desc\n",
                Map.of(
                        "m1", spec("manual", Set.of("file_read")),
                        "m2", spec("manual", Set.of("file_read", "file_write"))));

        String filtered = CatalogFilter.filter(snap, Set.of("file_read", "file_write"));

        assertThat(filtered).contains("### m1");
        assertThat(filtered).contains("### m2");
    }

    @Test
    void skill_entry_never_dropped() {
        CatalogSnapshot snap = snapshot(
                "## Skills\n\n### my-skill\n\nskill desc\n",
                Map.of("my-skill", spec("skill", Set.of())));

        // Even with empty allow-set, skills pass through (they have
        // their own activation mechanism).
        String filtered = CatalogFilter.filter(snap, Set.of("anything"));
        assertThat(filtered).contains("### my-skill");
    }

    @Test
    void section_header_kept_even_when_all_entries_dropped() {
        CatalogSnapshot snap = snapshot(
                "## Tools\n\n### foo\n\nfoo desc\n\n## Manuals\n\n### m1\n\nm1 desc\n",
                Map.of(
                        "foo", spec("tool", Set.of("foo")),
                        "m1", spec("manual", Set.of("foo"))));

        String filtered = CatalogFilter.filter(snap, Set.of("bar"));

        assertThat(filtered).contains("## Tools");
        assertThat(filtered).contains("## Manuals");
        assertThat(filtered).doesNotContain("### foo");
        assertThat(filtered).doesNotContain("### m1");
    }

    @Test
    void entries_to_drop_lists_filtered_names() {
        Map<String, CatalogSnapshot.EntrySpec> entries = Map.of(
                "foo", spec("tool", Set.of("foo")),
                "bar", spec("tool", Set.of("bar")),
                "m1", spec("manual", Set.of("research_investigate")),
                "m2", spec("manual", Set.of()));

        Set<String> drop = CatalogFilter.entriesToDrop(entries, Set.of("foo"));

        assertThat(drop).containsExactlyInAnyOrder("bar", "m1");
    }

    private static CatalogSnapshot snapshot(
            String markdown, Map<String, CatalogSnapshot.EntrySpec> entries) {
        return new CatalogSnapshot(markdown, "test-hash", entries);
    }

    private static CatalogSnapshot.EntrySpec spec(String type, Set<String> requires) {
        return new CatalogSnapshot.EntrySpec(type, requires);
    }
}
