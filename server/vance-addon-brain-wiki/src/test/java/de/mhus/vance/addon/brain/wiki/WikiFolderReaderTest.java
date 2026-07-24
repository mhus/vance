package de.mhus.vance.addon.brain.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-function coverage for the wiki's deterministic core: the
 * {@code [[target|label]]} extractor and {@code slugify} (which produces
 * on-disk filenames and must stay stable, mirroring the client slug rule).
 * A drift here silently mis-routes links or breaks red-link filename parity.
 */
class WikiFolderReaderTest {

    // ── extractLinks ─────────────────────────────────────────────────────

    @Test
    void extractLinks_plainTarget() {
        List<WikiLink> links = WikiFolderReader.extractLinks("see [[Home]] please");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).target()).isEqualTo("Home");
        assertThat(links.get(0).label()).isNull();
    }

    @Test
    void extractLinks_labelPipe() {
        List<WikiLink> links = WikiFolderReader.extractLinks("[[Getting Started|start here]]");
        assertThat(links.get(0).target()).isEqualTo("Getting Started");
        assertThat(links.get(0).label()).isEqualTo("start here");
    }

    @Test
    void extractLinks_blankLabel_isNull() {
        List<WikiLink> links = WikiFolderReader.extractLinks("[[Home|   ]]");
        assertThat(links.get(0).target()).isEqualTo("Home");
        assertThat(links.get(0).label()).isNull();
    }

    @Test
    void extractLinks_emptyTarget_skipped() {
        assertThat(WikiFolderReader.extractLinks("[[   ]] and [[|label]]")).isEmpty();
    }

    @Test
    void extractLinks_multiplePerLine_inOrder() {
        List<WikiLink> links = WikiFolderReader.extractLinks("[[A]] then [[B|b]] then [[C]]");
        assertThat(links).extracting(WikiLink::target).containsExactly("A", "B", "C");
    }

    // ── slugify ──────────────────────────────────────────────────────────

    @Test
    void slugify_lowercasesAndDashesSpaces() {
        assertThat(WikiFolderReader.slugify("Getting Started")).isEqualTo("getting-started");
    }

    @Test
    void slugify_collapsesSeparatorRuns_andTrimsTrailing() {
        assertThat(WikiFolderReader.slugify("  Hello --  World / .. ")).isEqualTo("hello-world");
    }

    @Test
    void slugify_keepsAlnumAndUnderscore() {
        assertThat(WikiFolderReader.slugify("api_v2 Notes")).isEqualTo("api_v2-notes");
    }

    @Test
    void slugify_nonAsciiPunctuationBecomesDash_collapsed() {
        assertThat(WikiFolderReader.slugify("C++ & Rust!!")).isEqualTo("c-rust");
    }

    @Test
    void slugify_nullOrAllSeparators_yieldsEmpty() {
        assertThat(WikiFolderReader.slugify(null)).isEmpty();
        assertThat(WikiFolderReader.slugify("---")).isEmpty();
    }

    // ── humanise ─────────────────────────────────────────────────────────

    @Test
    void humanise_dashesAndUnderscoresToSpaces_capitalised() {
        assertThat(WikiFolderReader.humanise("getting-started_now")).isEqualTo("Getting started now");
    }

    @Test
    void humanise_empty_isUntitled() {
        assertThat(WikiFolderReader.humanise("")).isEqualTo("Untitled");
    }
}
