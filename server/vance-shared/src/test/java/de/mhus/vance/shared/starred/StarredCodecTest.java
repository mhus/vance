package de.mhus.vance.shared.starred;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire behaviour of the {@code vance-starred} control file. The two properties
 * under test are the ones the design leans on: a read never throws, and a write
 * never drops what it did not write.
 */
class StarredCodecTest {

    private static final String LOC = "_vance/config/starred.yaml";

    // ── parse ───────────────────────────────────────────────────────

    @Test
    void parse_blankBody_yieldsEmptyListWithoutFindings() {
        StarredCodec.Result r = StarredCodec.parse("  ", LOC);

        assertThat(r.document().items()).isEmpty();
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void parse_missingItemsKey_isNotAnError() {
        StarredCodec.Result r = StarredCodec.parse("""
                $meta:
                  kind: vance-starred
                """, LOC);

        assertThat(r.document().items()).isEmpty();
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void parse_fullEntry_readsEveryField() {
        StarredCodec.Result r = StarredCodec.parse("""
                $meta:
                  kind: vance-starred
                items:
                  - project: _user_mhu
                    path: links/_app.yaml
                    kind: application
                    type: links
                    title: My links
                    description: Reading list
                    highlight: true
                    hidden: true
                """, LOC);

        assertThat(r.findings()).isEmpty();
        assertThat(r.document().items()).singleElement().satisfies(item -> {
            assertThat(item.project()).isEqualTo("_user_mhu");
            assertThat(item.path()).isEqualTo("links/_app.yaml");
            assertThat(item.kind()).isEqualTo("application");
            assertThat(item.type()).isEqualTo("links");
            assertThat(item.title()).isEqualTo("My links");
            assertThat(item.description()).isEqualTo("Reading list");
            assertThat(item.highlight()).isTrue();
            assertThat(item.enabled()).isTrue();
            assertThat(item.hidden()).isTrue();
            assertThat(item.visibility()).isEqualTo(StarredVisibility.HIDDEN);
        });
    }

    @Test
    void parse_defaults_enabledTrueEverythingElseFalse() {
        StarredCodec.Result r = StarredCodec.parse("""
                items:
                  - project: p
                    path: a.md
                    kind: text
                """, LOC);

        assertThat(r.document().items()).singleElement().satisfies(item -> {
            assertThat(item.enabled()).isTrue();
            assertThat(item.hidden()).isFalse();
            assertThat(item.highlight()).isFalse();
            assertThat(item.type()).isNull();
            assertThat(item.visibility()).isEqualTo(StarredVisibility.VISIBLE);
        });
    }

    @Test
    void parse_brokenYaml_reportsAndReturnsEmpty() {
        StarredCodec.Result r = StarredCodec.parse("items: [ unclosed", LOC);

        assertThat(r.document().items()).isEmpty();
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.code()).isEqualTo("vance-starred-parse");
        });
    }

    @Test
    void parse_entryWithoutPath_isSkippedAndTheRestSurvives() {
        StarredCodec.Result r = StarredCodec.parse("""
                items:
                  - project: p
                    kind: text
                  - project: p
                    path: good.md
                    kind: text
                """, LOC);

        assertThat(r.document().items()).singleElement()
                .satisfies(i -> assertThat(i.path()).isEqualTo("good.md"));
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.message()).contains("missing `path`");
            assertThat(f.location()).isEqualTo(LOC + "#items[0]");
        });
    }

    @Test
    void parse_missingKind_warnsAndFallsBackToText() {
        StarredCodec.Result r = StarredCodec.parse("""
                items:
                  - project: p
                    path: a.md
                """, LOC);

        assertThat(r.document().items()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo("text"));
        assertThat(r.findings()).singleElement()
                .satisfies(f -> assertThat(f.level()).isEqualTo(Finding.Level.WARNING));
    }

    @Test
    void parse_nonBooleanEnabled_warnsAndKeepsTheDefault() {
        StarredCodec.Result r = StarredCodec.parse("""
                items:
                  - project: p
                    path: a.md
                    kind: text
                    enabled: yesish
                """, LOC);

        // Falls back to the default rather than reading "not false" as false —
        // a typo must not silently disable an entry.
        assertThat(r.document().items()).singleElement()
                .satisfies(i -> assertThat(i.enabled()).isTrue());
        assertThat(r.findings()).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("`enabled` must be true or false"));
    }

    @Test
    void parse_quotedBooleanString_isAccepted() {
        StarredCodec.Result r = StarredCodec.parse("""
                items:
                  - project: p
                    path: a.md
                    kind: text
                    hidden: "true"
                """, LOC);

        assertThat(r.findings()).isEmpty();
        assertThat(r.document().items()).singleElement()
                .satisfies(i -> assertThat(i.hidden()).isTrue());
    }

    @Test
    void parse_itemsNotAList_reportsAndYieldsEmpty() {
        StarredCodec.Result r = StarredCodec.parse("items: nope", LOC);

        assertThat(r.document().items()).isEmpty();
        assertThat(r.findings()).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("vance-starred-items"));
    }

    // ── round-trip ──────────────────────────────────────────────────

    @Test
    void serialize_omitsDefaults() {
        String yaml = StarredCodec.serialize(new StarredDocument(List.of(
                StarredItem.builder().project("p").path("a.md").kind("text").build()),
                java.util.Map.of()));

        assertThat(yaml).contains("kind: vance-starred");
        assertThat(yaml).contains("project: p");
        assertThat(yaml).doesNotContain("enabled:");
        assertThat(yaml).doesNotContain("hidden:");
        assertThat(yaml).doesNotContain("highlight:");
    }

    @Test
    void serialize_writesNonDefaultSwitches() {
        String yaml = StarredCodec.serialize(new StarredDocument(List.of(
                StarredItem.builder().project("p").path("a.md").kind("text")
                        .enabled(false).hidden(true).highlight(true).build()),
                java.util.Map.of()));

        assertThat(yaml).contains("enabled: false");
        assertThat(yaml).contains("hidden: true");
        assertThat(yaml).contains("highlight: true");
    }

    @Test
    void roundTrip_preservesUnknownEntryFields() {
        String original = """
                $meta:
                  kind: vance-starred
                items:
                  - project: p
                    path: a.md
                    kind: text
                    somethingNew: keep-me
                """;

        StarredDocument doc = StarredCodec.parseLenient(original);
        assertThat(doc.items()).singleElement()
                .satisfies(i -> assertThat(i.extra()).containsEntry("somethingNew", "keep-me"));

        String again = StarredCodec.serialize(doc);
        assertThat(again).contains("somethingNew: keep-me");

        // And a second cycle is stable.
        assertThat(StarredCodec.serialize(StarredCodec.parseLenient(again))).isEqualTo(again);
    }

    @Test
    void roundTrip_preservesUnknownTopLevelKeys() {
        String original = """
                $meta:
                  kind: vance-starred
                note: written by a human
                items: []
                """;

        String again = StarredCodec.serialize(StarredCodec.parseLenient(original));

        assertThat(again).contains("note: written by a human");
    }

    @Test
    void roundTrip_keepsFileOrder() {
        String yaml = StarredCodec.serialize(new StarredDocument(List.of(
                StarredItem.builder().project("p").path("b.md").kind("text").build(),
                StarredItem.builder().project("p").path("a.md").kind("text").build()),
                java.util.Map.of()));

        assertThat(StarredCodec.parseLenient(yaml).items())
                .extracting(StarredItem::path)
                .containsExactly("b.md", "a.md");
    }
}
