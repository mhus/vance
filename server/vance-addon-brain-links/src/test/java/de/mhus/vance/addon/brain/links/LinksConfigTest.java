package de.mhus.vance.addon.brain.links;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * A manifest is a file a person edits, so every test here asks the same
 * thing: does a mistake in that file cost one row or the whole app?
 */
class LinksConfigTest {

    @Test
    void from_readsEntriesAndGroups() {
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "groups", List.of("Rust", "Later"),
                "entries", List.of(
                        Map.of("url", "https://a.example/1", "group", "Rust",
                                "title", "One", "tags", List.of("x")),
                        Map.of("url", "https://b.example/2")))));

        assertThat(config.groups()).containsExactly("Rust", "Later");
        assertThat(config.entries()).hasSize(2);
        assertThat(config.entries().getFirst().title()).isEqualTo("One");
        assertThat(config.entries().getFirst().tags()).containsExactly("x");
        assertThat(config.entries().getLast().group()).isNull();
    }

    @Test
    void from_missingBlockIsAnEmptyList() {
        assertThat(LinksConfig.from(manifest(null)).entries()).isEmpty();
        assertThat(LinksConfig.from(manifest(null)).indexOutputPath())
                .isEqualTo(LinksConfig.DEFAULT_INDEX);
    }

    @Test
    void from_readsTheBareUrlShortForm() {
        // The form a person writes when editing the YAML by hand.
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "entries", List.of("example.com/x"))));

        assertThat(config.entries()).singleElement()
                .extracting(LinkEntry::url).isEqualTo("https://example.com/x");
    }

    @Test
    void from_skipsAnUnusableRowRatherThanRefusingTheManifest() {
        // One typo must not take down the app you would fix the typo in.
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "entries", List.of(
                        Map.of("url", "javascript:alert(1)"),
                        Map.of("title", "no url here"),
                        Map.of("url", "https://ok.example/")))));

        assertThat(config.entries()).singleElement()
                .extracting(LinkEntry::url).isEqualTo("https://ok.example/");
    }

    @Test
    void orderedGroups_appendsGroupsThatWereNeverDeclared() {
        // A hand-written manifest should not have to declare anything.
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "groups", List.of("Declared"),
                "entries", List.of(
                        Map.of("url", "https://a.example/", "group", "Ad hoc"),
                        Map.of("url", "https://b.example/", "group", "Declared")))));

        assertThat(config.orderedGroups()).containsExactly("Declared", "Ad hoc");
    }

    @Test
    void orderedGroups_keepsAnEmptyDeclaredGroup() {
        // The whole reason `groups` is not derived from the entries: a group
        // created before it has anything in it has to survive.
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "groups", List.of("To read"), "entries", List.of())));

        assertThat(config.orderedGroups()).containsExactly("To read");
    }

    @Test
    void entriesOf_blankSelectsTheUngroupedLeadGroup() {
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "entries", List.of(
                        Map.of("url", "https://a.example/"),
                        Map.of("url", "https://b.example/", "group", "G")))));

        assertThat(config.entriesOf("")).singleElement()
                .extracting(LinkEntry::url).isEqualTo("https://a.example/");
        assertThat(config.entriesOf(null)).hasSize(1);
        assertThat(config.entriesOf("G")).hasSize(1);
    }

    @Test
    void toBlock_roundTrips() {
        LinksConfig original = LinksConfig.from(manifest(Map.of(
                "groups", List.of("G"),
                "entries", List.of(Map.of("url", "https://a.example/x",
                        "title", "T", "teaser", "S", "group", "G",
                        "tags", List.of("t1", "t2"), "note", "N")))));

        LinksConfig again = LinksConfig.from(manifestFromBlock(original.toBlock()));

        assertThat(again.groups()).isEqualTo(original.groups());
        assertThat(again.entries()).isEqualTo(original.entries());
        assertThat(again.indexOutputPath()).isEqualTo(original.indexOutputPath());
    }

    @Test
    void toBlock_omitsEmptyOverridesInsteadOfWritingNull() {
        // An empty teaser means "ask the page", so it must not be persisted as
        // a key at all — a null in the YAML would read as an override to blank.
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "entries", List.of(Map.of("url", "https://a.example/")))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) config.toBlock().get("entries");

        assertThat(rows).singleElement().satisfies(row ->
                assertThat(row).containsOnlyKeys("url"));
    }

    @Test
    void from_readsACustomIndexPath() {
        LinksConfig config = LinksConfig.from(manifest(Map.of(
                "index", Map.of("outputPath", "overview.md"))));

        assertThat(config.indexOutputPath()).isEqualTo("overview.md");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static ApplicationDocument manifest(@Nullable Map<String, Object> block) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (block != null) config.put(LinksConfig.BLOCK, block);
        return new ApplicationDocument("application", LinksConfig.BLOCK,
                "Links", null, config, new LinkedHashMap<>());
    }

    private static ApplicationDocument manifestFromBlock(Map<String, Object> block) {
        return manifest(block);
    }
}
