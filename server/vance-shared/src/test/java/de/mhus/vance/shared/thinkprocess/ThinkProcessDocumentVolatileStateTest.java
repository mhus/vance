package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the in-JVM shape of the volatile context-assembly state on
 * {@link ThinkProcessDocument}: defaults are empty mutable collections,
 * builder preserves entries, {@link ReadStateEntry} rejects blank
 * required fields. Mongo round-trip is Spring's contract — not exercised.
 */
class ThinkProcessDocumentVolatileStateTest {

    @Test
    void defaultConstructor_yieldsEmptyMutableReadStateAndShownOnce() {
        ThinkProcessDocument doc = new ThinkProcessDocument();

        assertThat(doc.getReadState()).isNotNull().isEmpty();
        assertThat(doc.getShownOnce()).isNotNull().isEmpty();

        doc.getReadState().add(entry("CLIENT_FILE:/abs/Foo.java", "h1"));
        doc.getShownOnce().add("CLAUDE.md");
        assertThat(doc.getReadState()).hasSize(1);
        assertThat(doc.getShownOnce()).containsExactly("CLAUDE.md");
    }

    @Test
    void builder_withoutVolatileState_yieldsEmptyMutableCollections() {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .name("p").build();

        assertThat(doc.getReadState()).isNotNull().isEmpty();
        assertThat(doc.getShownOnce()).isNotNull().isEmpty();
    }

    @Test
    void builder_withReadStateEntries_preservesOrder() {
        ReadStateEntry first = entry("CLIENT_FILE:/abs/A.java", "h1");
        ReadStateEntry second = entry("DOCUMENT:65f-doc", "h2");

        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .name("p")
                .readState(new java.util.ArrayList<>(List.of(first, second)))
                .build();

        assertThat(doc.getReadState()).containsExactly(first, second);
    }

    @Test
    void builder_withShownOnceMarkers_preservesInsertionOrder() {
        java.util.Set<String> markers = new LinkedHashSet<>();
        markers.add("CLAUDE.md");
        markers.add("kit-welcome");

        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .name("p")
                .shownOnce(markers)
                .build();

        assertThat(doc.getShownOnce()).containsExactly("CLAUDE.md", "kit-welcome");
    }

    @Test
    void readStateEntry_blankKey_rejected() {
        assertThatThrownBy(() ->
                new ReadStateEntry("", "h", Instant.now(), false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void readStateEntry_blankContentHash_rejected() {
        assertThatThrownBy(() ->
                new ReadStateEntry("CLIENT_FILE:/x", "", Instant.now(), false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentHash");
    }

    @Test
    void readStateEntry_nullFetchedAt_rejected() {
        assertThatThrownBy(() ->
                new ReadStateEntry("CLIENT_FILE:/x", "h", null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetchedAt");
    }

    @Test
    void readStateEntry_partialViewAndBytes_carriedAsIs() {
        ReadStateEntry e = new ReadStateEntry(
                "CLIENT_FILE:/abs/CLAUDE.md", "h",
                Instant.parse("2026-05-11T08:00:00Z"),
                /*partialView*/ true,
                /*bytesAtFetch*/ 24_576L);

        assertThat(e.partialView()).isTrue();
        assertThat(e.bytesAtFetch()).isEqualTo(24_576L);
    }

    private static ReadStateEntry entry(String key, String hash) {
        return new ReadStateEntry(key, hash, Instant.now(), false, null);
    }
}
