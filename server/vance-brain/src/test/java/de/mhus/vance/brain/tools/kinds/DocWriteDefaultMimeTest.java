package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What a {@code doc_write} without an explicit {@code mimeType} stores.
 *
 * <p>The regression this pins: an agent writing a designer-app's
 * {@code index.html} used to get {@code text/markdown} — the kind default —
 * and the sandboxed preview (nosniff) showed raw source instead of the
 * design. The extension is the author's file-type declaration; the kind
 * default only answers for paths without a recognisable one.
 */
class DocWriteDefaultMimeTest {

    @Test
    void htmlAndCssPathsDeriveTheirMimeFromTheExtension() {
        assertThat(DocWriteTool.defaultMime("text", "designs/landing/index.html"))
                .isEqualTo("text/html");
        assertThat(DocWriteTool.defaultMime("text", "designs/landing/style.css"))
                .isEqualTo("text/css");
        assertThat(DocWriteTool.defaultMime("text", "designs/landing/script.js"))
                .isEqualTo("text/javascript");
    }

    @Test
    void markdownPathsMatchTheOldKindDefault() {
        // Unchanged behaviour for the common case: text/records/… on .md
        // used to get text/markdown from the kind, and still does (now via
        // the extension — same answer, different road).
        assertThat(DocWriteTool.defaultMime("text", "notes/todo.md")).isEqualTo("text/markdown");
        assertThat(DocWriteTool.defaultMime("records", "data/rows.md")).isEqualTo("text/markdown");
    }

    @Test
    void extensionlessOrUnknownPathsFallBackToTheKindDefault() {
        assertThat(DocWriteTool.defaultMime("text", "readme")).isEqualTo("text/markdown");
        assertThat(DocWriteTool.defaultMime("text", "bin.dat")).isEqualTo("text/markdown");
        assertThat(DocWriteTool.defaultMime("data", "blob")).isEqualTo("application/json");
    }
}
