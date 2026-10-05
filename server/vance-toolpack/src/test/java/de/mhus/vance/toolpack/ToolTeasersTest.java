package de.mhus.vance.toolpack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Teaser coverage for the tool families that have one, the caps, and the
 * sanitising of echoed values.
 */
class ToolTeasersTest {

    // ------------------------------------------------------------------
    // call() — subject lines
    // ------------------------------------------------------------------

    @Test
    void call_fileRead_showsThePath() {
        assertThat(ToolTeasers.call("file_read", Map.of("path", "src/Main.java")))
                .isEqualTo("src/Main.java");
    }

    @Test
    void call_fileWrite_showsPathAndSize() {
        Map<String, Object> params = Map.of("path", "notes.md", "content", "a\nb\nc");
        assertThat(ToolTeasers.call("file_write", params)).isEqualTo("notes.md, 3 lines, 5 chars");
    }

    @Test
    void call_fileEdit_showsPathAndReplacementSize() {
        Map<String, Object> params = Map.of("path", "notes.md", "oldText", "abcd", "newText", "x");
        assertThat(ToolTeasers.call("file_edit", params)).isEqualTo("notes.md, replace 4 chars");
    }

    @Test
    void call_fileGrep_showsPatternAndPath() {
        assertThat(ToolTeasers.call("file_grep", Map.of("pattern", "TODO", "path", "src")))
                .isEqualTo("/TODO/ src");
    }

    @Test
    void call_fileFind_showsGlobAndPath() {
        assertThat(ToolTeasers.call("file_find", Map.of("pathGlob", "*.java", "path", "src")))
                .isEqualTo("*.java in src");
    }

    @Test
    void call_execRun_showsTheCommand() {
        assertThat(ToolTeasers.call("exec_run", Map.of("command", "mvn -q test")))
                .isEqualTo("mvn -q test");
    }

    @Test
    void call_clientPrefixedName_matchesTheWrapperName() {
        // The foot backend carries the same params under its client_* name —
        // one case per family must serve both.
        assertThat(ToolTeasers.call("client_file_read", Map.of("path", "a.txt")))
                .isEqualTo("a.txt");
    }

    @Test
    void call_docTool_showsTheDocumentSubject() {
        assertThat(ToolTeasers.call("doc_edit", Map.of("documentId", "6523…", "path", "spec/arch.md")))
                .isEqualTo("spec/arch.md");
    }

    @Test
    void call_unknownTool_hasNoTeaser() {
        assertThat(ToolTeasers.call("process_create", Map.of("recipe", "coding")))
                .isNull();
    }

    @Test
    void call_emptyParams_hasNoTeaser() {
        assertThat(ToolTeasers.call("file_read", Map.of())).isNull();
    }

    // ------------------------------------------------------------------
    // call() — caps and sanitising
    // ------------------------------------------------------------------

    @Test
    void call_controlBytes_areStripped() {
        String hostile = "a\u001B]0;owned\u0007.txt\u0000";
        String teaser = ToolTeasers.call("file_read", Map.of("path", hostile));
        assertThat(teaser).doesNotContain("\u001B").doesNotContain("\u0000").doesNotContain("\u0007");
    }

    @Test
    void call_longPath_isCappedWithEllipsis() {
        String path = "x".repeat(500);
        String teaser = ToolTeasers.call("file_read", Map.of("path", path));
        assertThat(teaser).hasSize(ToolTeasers.MAX_LINE_CHARS);
        assertThat(teaser).endsWith("…");
    }

    // ------------------------------------------------------------------
    // preview() / describe() — the patch-style block
    // ------------------------------------------------------------------

    @Test
    void describe_fileWrite_addsPlusPreviewLines() {
        Map<String, Object> params = Map.of("path", "a.txt", "content", "one\ntwo");
        assertThat(ToolTeasers.describe("file_write", params)).isEqualTo("a.txt, 2 lines, 7 chars\n+ one\n+ two");
    }

    @Test
    void describe_fileWrite_capsThePreviewAtFiveLines() {
        String content = String.join("\n", "1", "2", "3", "4", "5", "6", "7");
        Map<String, Object> params = Map.of("path", "a.txt", "content", content);
        String teaser = ToolTeasers.describe("file_write", params);
        assertThat(teaser).doesNotContain("+ 6").endsWith("…");
    }

    @Test
    void describe_fileEdit_showsAMiniDiff() {
        Map<String, Object> params = Map.of("path", "a.txt", "oldText", "alpha\nbeta", "newText", "alpha\ngamma");
        assertThat(ToolTeasers.describe("file_edit", params))
                .isEqualTo("a.txt, replace 10 chars\n- alpha\n- beta\n+ alpha\n+ gamma");
    }

    @Test
    void preview_fileRead_hasNoPreviewBlock() {
        assertThat(ToolTeasers.preview("file_read", Map.of("path", "a.txt"))).isNull();
    }

    @Test
    void describe_longPreview_isCappedInLinesAndChars() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            content.append("line ").append(i).append('\n');
        }
        String teaser = ToolTeasers.describe("file_write", Map.of("path", "a.txt", "content", content.toString()));
        assertThat(teaser.split("\n", -1).length).isLessThanOrEqualTo(ToolTeasers.MAX_LINES);
        assertThat(teaser.length()).isLessThanOrEqualTo(ToolTeasers.MAX_CHARS);
    }

    // ------------------------------------------------------------------
    // outcome() — result summaries
    // ------------------------------------------------------------------

    @Test
    void outcome_fileRead_showsCharsAndTruncation() {
        assertThat(ToolTeasers.outcome("file_read", Map.of("totalChars", 1234, "truncated", true)))
                .isEqualTo("Read 1234 chars (truncated)");
    }

    @Test
    void outcome_fileWrite_showsChars() {
        assertThat(ToolTeasers.outcome("file_write", Map.of("chars", 42))).isEqualTo("Wrote 42 chars");
    }

    @Test
    void outcome_execRun_showsExitCodeAndDuration() {
        assertThat(ToolTeasers.outcome("exec_run", Map.of("status", "exited", "exitCode", 0, "durationMs", 1200)))
                .isEqualTo("exited (exit 0), 1200 ms");
    }

    @Test
    void outcome_webSearch_showsResultCount() {
        assertThat(ToolTeasers.outcome("web_search", Map.of("count", 3))).isEqualTo("3 results");
    }

    @Test
    void outcome_resultWithoutKnownKeys_hasNoTeaser() {
        assertThat(ToolTeasers.outcome("file_write", Map.of("something", "else")))
                .isNull();
    }

    @Test
    void outcome_errorResult_showsTheError() {
        assertThat(ToolTeasers.outcome("client_javascript", Map.of("error", "boom")))
                .isEqualTo("Error: boom");
    }

    @Test
    void outcome_controlBytes_areStripped() {
        assertThat(ToolTeasers.outcome("javascript", Map.of("error", "bad\u001B[31m")))
                .doesNotContain("\u001B");
    }

    @Test
    void outcome_nullResult_hasNoTeaser() {
        assertThat(ToolTeasers.outcome("file_read", null)).isNull();
    }

    @Test
    void describe_nullParams_hasNoTeaser() {
        assertThat(ToolTeasers.describe("file_read", null)).isNull();
    }
}
