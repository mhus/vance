package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the four foot-side read-only file tools
 * ({@code client_file_grep}, {@code client_file_find},
 * {@code client_file_head_tail}, {@code client_file_count}).
 *
 * <p>The foot tools work directly against a path the LLM hands in —
 * no Spring context, no mocks; a real temp directory is the natural
 * test surface.
 */
class ClientFileReadOnlyToolsTest {

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-client-readonly-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    private Path writeFile(String relPath, String content) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ──────────────── grep ────────────────

    @Test
    void grep_findsMatchesAcrossFiles_withGlob() throws IOException {
        writeFile("docs/notes.md", "# Title\nTODO buy milk\n");
        writeFile("docs/nested/sub.md", "TODO ship\nplain\n");
        writeFile("docs/ignore.txt", "TODO this should be skipped\n");

        ClientFileGrepTool tool = new ClientFileGrepTool();
        Map<String, Object> result = tool.invoke(Map.of(
                "pattern", "TODO",
                "path", root.toString(),
                "pathGlob", "**/*.md"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(m -> m.get("path"))
                .containsExactlyInAnyOrder("docs/notes.md", "docs/nested/sub.md");
        assertThat(result).containsEntry("filesScanned", 2);
    }

    @Test
    void grep_caseInsensitive_andContext() throws IOException {
        writeFile("a.txt", "alpha\nGAMMA\nbeta\n");

        ClientFileGrepTool tool = new ClientFileGrepTool();
        Map<String, Object> result = tool.invoke(Map.of(
                "pattern", "gamma",
                "path", root.toString(),
                "caseInsensitive", true,
                "contextBefore", 1,
                "contextAfter", 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ctx = (List<Map<String, Object>>) matches.get(0).get("context");
        assertThat(ctx).hasSize(2);
    }

    @Test
    void grep_invalidRegex_throwsIllegalArg() {
        ClientFileGrepTool tool = new ClientFileGrepTool();
        assertThatThrownBy(() -> tool.invoke(Map.of(
                "pattern", "[unclosed",
                "path", root.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid regex");
    }

    @Test
    void grep_carriesReadOnlyLabel_only() {
        ClientFileGrepTool tool = new ClientFileGrepTool();
        assertThat(tool.labels()).containsExactly("read-only");
    }

    // ──────────────── find ────────────────

    @Test
    void find_byGlob_andSizeRange() throws IOException {
        writeFile("docs/small.md", "x\n");                  // 2 bytes
        writeFile("docs/big.md", "x".repeat(500) + "\n");   // 501 bytes
        writeFile("docs/ignore.txt", "x\n");

        ClientFileFindTool tool = new ClientFileFindTool();
        Map<String, Object> result = tool.invoke(Map.of(
                "path", root.toString(),
                "pathGlob", "**/*.md",
                "minSizeBytes", 100));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries).hasSize(1).first()
                .extracting(e -> e.get("path")).isEqualTo("docs/big.md");
    }

    @Test
    void find_sortByMtimeDesc() throws IOException {
        Path a = writeFile("a.md", "1\n");
        Path b = writeFile("b.md", "2\n");
        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(b, FileTime.from(Instant.parse("2026-05-01T00:00:00Z")));

        ClientFileFindTool tool = new ClientFileFindTool();
        Map<String, Object> result = tool.invoke(Map.of(
                "path", root.toString(),
                "sortBy", "mtime"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries.get(0)).containsEntry("path", "b.md");
        assertThat(entries.get(1)).containsEntry("path", "a.md");
    }

    @Test
    void find_invalidInstant_throws() {
        ClientFileFindTool tool = new ClientFileFindTool();
        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", root.toString(),
                "modifiedAfter", "yesterday")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    // ──────────────── head_tail ────────────────

    @Test
    void headTail_returnsBothSlices() throws IOException {
        writeFile("file.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");
        ClientFileHeadTailTool tool = new ClientFileHeadTailTool();

        Map<String, Object> result = tool.invoke(Map.of(
                "path", root.resolve("file.txt").toString(),
                "head", 2,
                "tail", 2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> head = (List<Map<String, Object>>) result.get("head");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tail = (List<Map<String, Object>>) result.get("tail");
        assertThat(head).hasSize(2);
        assertThat(tail).hasSize(2);
        assertThat(head.get(0)).containsEntry("line", "1");
        assertThat(tail.get(1)).containsEntry("line", "10");
    }

    @Test
    void headTail_neitherZero_rejected() throws IOException {
        writeFile("file.txt", "x\n");
        ClientFileHeadTailTool tool = new ClientFileHeadTailTool();
        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", root.resolve("file.txt").toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head");
    }

    @Test
    void headTail_missingFile_throws() {
        ClientFileHeadTailTool tool = new ClientFileHeadTailTool();
        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", root.resolve("nope.txt").toString(),
                "head", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not found");
    }

    // ──────────────── count ────────────────

    @Test
    void count_singleFile() throws IOException {
        writeFile("a.txt", "hello\nworld\n");
        ClientFileCountTool tool = new ClientFileCountTool();

        Map<String, Object> result = tool.invoke(Map.of(
                "path", root.resolve("a.txt").toString()));

        assertThat(result).containsEntry("filesCounted", 1);
        // wc -l semantics: 2 newline-terminated lines.
        assertThat(((Number) result.get("lines")).longValue()).isEqualTo(2L);
        assertThat(((Number) result.get("chars")).longValue()).isEqualTo(12L);
        assertThat(((Number) result.get("bytes")).longValue()).isEqualTo(12L);
    }

    @Test
    void count_directory_withRegex() throws IOException {
        writeFile("a.md", "TODO buy milk\nplain\nTODO ship\n");
        writeFile("b.md", "todo lowercase\n");

        ClientFileCountTool tool = new ClientFileCountTool();
        Map<String, Object> result = tool.invoke(Map.of(
                "path", root.toString(),
                "pattern", "TODO",
                "caseInsensitive", true));

        // a.md has 2 TODO matches, b.md has 1 → 3 matching lines total.
        assertThat(((Number) result.get("lines")).longValue()).isEqualTo(3L);
        // a.md has 3 lines + b.md has 1 line = 4 total scanned.
        assertThat(((Number) result.get("totalLinesScanned")).longValue()).isEqualTo(4L);
    }

    @Test
    void count_carriesReadOnlyLabel_only() {
        ClientFileCountTool tool = new ClientFileCountTool();
        assertThat(tool.labels()).containsExactly("read-only");
    }
}
