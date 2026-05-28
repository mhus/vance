package de.mhus.vance.brain.tools.r;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure helpers on {@link RScriptTool}. The Rserve roundtrip itself
 * is opt-in integration territory (needs a live daemon) and lives
 * elsewhere.
 */
class RScriptToolTest {

    @Test
    void combine_bothEmpty_returnsEmpty() {
        assertThat(RScriptTool.combine("", "")).isEmpty();
        assertThat(RScriptTool.combine(null, null)).isEmpty();
        assertThat(RScriptTool.combine("  ", "\n")).isEmpty();
    }

    @Test
    void combine_outputOnly_returnsOutput() {
        assertThat(RScriptTool.combine("printed line", ""))
                .isEqualTo("printed line");
    }

    @Test
    void combine_valueOnly_returnsValue() {
        assertThat(RScriptTool.combine("", "[1] 42"))
                .isEqualTo("[1] 42");
    }

    @Test
    void combine_bothPresent_joinedWithNewline() {
        assertThat(RScriptTool.combine("hello world", "[1] 3.14"))
                .isEqualTo("hello world\n[1] 3.14");
    }

    @Test
    void combine_stripsBoundaryWhitespace() {
        assertThat(RScriptTool.combine("  hello\n", "\n[1] 42\n"))
                .isEqualTo("hello\n[1] 42");
    }

    // ─── kindForExtension ───────────────────────────────────────────

    @Test
    void kindForExtension_images() {
        assertThat(RScriptTool.kindForExtension("plot.png")).isEqualTo("image");
        assertThat(RScriptTool.kindForExtension("plot.PNG")).isEqualTo("image");
        assertThat(RScriptTool.kindForExtension("photo.jpg")).isEqualTo("image");
        assertThat(RScriptTool.kindForExtension("photo.jpeg")).isEqualTo("image");
        assertThat(RScriptTool.kindForExtension("anim.gif")).isEqualTo("image");
    }

    @Test
    void kindForExtension_vectorAndDocs() {
        assertThat(RScriptTool.kindForExtension("plot.svg")).isEqualTo("svg");
        assertThat(RScriptTool.kindForExtension("report.pdf")).isEqualTo("pdf");
    }

    @Test
    void kindForExtension_tabular() {
        assertThat(RScriptTool.kindForExtension("data.csv")).isEqualTo("records");
        assertThat(RScriptTool.kindForExtension("data.tsv")).isEqualTo("records");
        assertThat(RScriptTool.kindForExtension("payload.json")).isEqualTo("data");
    }

    @Test
    void kindForExtension_text() {
        assertThat(RScriptTool.kindForExtension("notes.md")).isEqualTo("markdown");
        assertThat(RScriptTool.kindForExtension("readme.markdown")).isEqualTo("markdown");
        assertThat(RScriptTool.kindForExtension("debug.log")).isEqualTo("text");
        assertThat(RScriptTool.kindForExtension("page.html")).isEqualTo("html");
    }

    @Test
    void kindForExtension_unknownDefaultsToDocument() {
        assertThat(RScriptTool.kindForExtension("data.rds")).isEqualTo("document");
        assertThat(RScriptTool.kindForExtension("noext")).isEqualTo("document");
    }

    // ─── mimeForExtension ───────────────────────────────────────────

    @Test
    void mimeForExtension_picksRightMimes() {
        assertThat(RScriptTool.mimeForExtension("plot.png")).isEqualTo("image/png");
        assertThat(RScriptTool.mimeForExtension("plot.svg")).isEqualTo("image/svg+xml");
        assertThat(RScriptTool.mimeForExtension("report.pdf")).isEqualTo("application/pdf");
        assertThat(RScriptTool.mimeForExtension("data.csv")).isEqualTo("text/csv");
        assertThat(RScriptTool.mimeForExtension("payload.json")).isEqualTo("application/json");
        assertThat(RScriptTool.mimeForExtension("notes.md")).isEqualTo("text/markdown");
    }

    @Test
    void mimeForExtension_unknownFallsBackToOctetStream() {
        assertThat(RScriptTool.mimeForExtension("data.rds"))
                .isEqualTo("application/octet-stream");
    }
}
