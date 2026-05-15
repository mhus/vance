package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the four workspace read-only tools
 * ({@code scratch_grep}, {@code scratch_find},
 * {@code scratch_head_tail}, {@code scratch_count}).
 *
 * <p>Each tool is exercised against a real temp filesystem with the
 * {@link WorkspaceService} mocked at the {@code list}/{@code resolve}
 * surface — that's enough to drive the tool's body without booting
 * Spring + Mongo.
 */
class WorkspaceReadOnlyToolsTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String DIR = "scratch";
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext(TENANT, PROJECT, "sess", "proc", "user");

    private Path root;
    private WorkspaceService workspace;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-ws-readonly-test-");
        workspace = mock(WorkspaceService.class);

        // resolve(tenant, project, dir, rel) returns root.resolve(rel).
        when(workspace.resolve(eq(TENANT), eq(PROJECT), eq(DIR), any()))
                .thenAnswer(inv -> root.resolve((String) inv.getArgument(3)));
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

    private void writeFile(String relPath, String content) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private void mockList(String... files) {
        when(workspace.list(TENANT, PROJECT, DIR)).thenReturn(List.of(files));
    }

    // ──────────────── scratch_grep ────────────────

    @Test
    void grep_findsMatchesAcrossFiles_withContext() throws IOException {
        writeFile("a.txt", "alpha\nbeta\nGAMMA\n");
        writeFile("nested/b.md", "delta\nGAMMA gamma\nepsilon\n");
        mockList("a.txt", "nested/b.md");

        WorkspaceGrepTool tool = new WorkspaceGrepTool(workspace);
        Map<String, Object> result = tool.invoke(
                Map.of("pattern", "gamma",
                        "dirName", DIR,
                        "caseInsensitive", true,
                        "contextBefore", 1),
                CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0)).containsEntry("path", "a.txt")
                .containsEntry("lineNumber", 3)
                .containsEntry("line", "GAMMA");
        assertThat(matches.get(0).get("context")).isInstanceOf(List.class);
        assertThat(result).containsEntry("filesScanned", 2)
                .containsEntry("matchCount", 2)
                .containsEntry("truncated", false);
    }

    @Test
    void grep_pathGlob_filtersScannedFiles() throws IOException {
        writeFile("docs/keep.md", "todo\n");
        writeFile("docs/skip.txt", "todo\n");
        mockList("docs/keep.md", "docs/skip.txt");

        WorkspaceGrepTool tool = new WorkspaceGrepTool(workspace);
        Map<String, Object> result = tool.invoke(
                Map.of("pattern", "todo",
                        "dirName", DIR,
                        "pathGlob", "**/*.md"),
                CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).containsEntry("path", "docs/keep.md");
        assertThat(result).containsEntry("filesScanned", 1);
    }

    @Test
    void grep_invalidRegex_throws() {
        mockList();
        WorkspaceGrepTool tool = new WorkspaceGrepTool(workspace);
        assertThatThrownBy(() -> tool.invoke(
                Map.of("pattern", "[unclosed",
                        "dirName", DIR), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Invalid regex");
    }

    @Test
    void grep_carriesReadOnlyLabel_only() {
        WorkspaceGrepTool tool = new WorkspaceGrepTool(workspace);
        assertThat(tool.labels()).containsExactly("read-only");
    }

    // ──────────────── scratch_find ────────────────

    @Test
    void find_filtersByGlob_andSortsByMtimeDesc() throws Exception {
        writeFile("docs/a.md", "old\n");
        writeFile("docs/b.md", "new\n");
        writeFile("docs/ignore.txt", "x\n");
        // Make b.md newer than a.md and ignore.txt by setting mtimes.
        Files.setLastModifiedTime(root.resolve("docs/a.md"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve("docs/b.md"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-05-01T00:00:00Z")));
        mockList("docs/a.md", "docs/b.md", "docs/ignore.txt");

        WorkspaceFindTool tool = new WorkspaceFindTool(workspace);
        Map<String, Object> result = tool.invoke(
                Map.of("dirName", DIR,
                        "pathGlob", "**/*.md",
                        "sortBy", "mtime"),
                CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("path", "docs/b.md");
        assertThat(entries.get(1)).containsEntry("path", "docs/a.md");
        assertThat(result).containsEntry("matchCount", 2);
    }

    @Test
    void find_modifiedAfter_excludesOlderFiles() throws Exception {
        writeFile("old.txt", "1\n");
        writeFile("new.txt", "2\n");
        Files.setLastModifiedTime(root.resolve("old.txt"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve("new.txt"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-04-01T00:00:00Z")));
        mockList("old.txt", "new.txt");

        WorkspaceFindTool tool = new WorkspaceFindTool(workspace);
        Map<String, Object> result = tool.invoke(
                Map.of("dirName", DIR,
                        "modifiedAfter", "2026-01-01T00:00:00Z"),
                CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries).hasSize(1).first()
                .extracting(e -> e.get("path")).isEqualTo("new.txt");
    }

    @Test
    void find_invalidInstant_throws() {
        mockList();
        WorkspaceFindTool tool = new WorkspaceFindTool(workspace);
        assertThatThrownBy(() -> tool.invoke(
                Map.of("dirName", DIR,
                        "modifiedAfter", "yesterday"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ISO-8601");
    }

    // ──────────────── scratch_head_tail ────────────────

    @Test
    void headTail_returnsBothSlices() throws IOException {
        writeFile("file.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");
        WorkspaceHeadTailTool tool = new WorkspaceHeadTailTool(workspace);

        Map<String, Object> result = tool.invoke(
                Map.of("path", "file.txt",
                        "dirName", DIR,
                        "head", 2,
                        "tail", 2),
                CTX);

        assertThat(result).containsEntry("totalLines", 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> head = (List<Map<String, Object>>) result.get("head");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tail = (List<Map<String, Object>>) result.get("tail");
        assertThat(head).hasSize(2);
        assertThat(head.get(0)).containsEntry("lineNumber", 1).containsEntry("line", "1");
        assertThat(tail).hasSize(2);
        assertThat(tail.get(1)).containsEntry("lineNumber", 10);
    }

    @Test
    void headTail_neitherZero_rejected() {
        WorkspaceHeadTailTool tool = new WorkspaceHeadTailTool(workspace);
        assertThatThrownBy(() -> tool.invoke(
                Map.of("path", "x", "dirName", DIR), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("head");
    }

    @Test
    void headTail_missingFile_throws() {
        WorkspaceHeadTailTool tool = new WorkspaceHeadTailTool(workspace);
        assertThatThrownBy(() -> tool.invoke(
                Map.of("path", "nope.txt",
                        "dirName", DIR,
                        "head", 5), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Not found");
    }

    // ──────────────── scratch_count ────────────────

    @Test
    void count_singleFile_linesAndChars() throws IOException {
        writeFile("a.txt", "hello\nworld\n");
        WorkspaceCountTool tool = new WorkspaceCountTool(workspace);

        Map<String, Object> result = tool.invoke(
                Map.of("path", "a.txt", "dirName", DIR), CTX);

        assertThat(result).containsEntry("filesCounted", 1);
        // wc -l semantics: 2 newline-terminated lines.
        assertThat(((Number) result.get("lines")).longValue()).isEqualTo(2L);
        assertThat(((Number) result.get("chars")).longValue()).isEqualTo(12L);
        assertThat(((Number) result.get("bytes")).longValue()).isEqualTo(12L);
    }

    @Test
    void count_directory_withRegex_countsMatchingLines() throws IOException {
        writeFile("a.md", "TODO foo\nplain\nTODO bar\n");
        writeFile("b.md", "todo lower\n");
        mockList("a.md", "b.md");

        WorkspaceCountTool tool = new WorkspaceCountTool(workspace);
        Map<String, Object> result = tool.invoke(
                Map.of("dirName", DIR,
                        "pattern", "TODO",
                        "caseInsensitive", true),
                CTX);

        // 3 lines match TODO across both files (a.md: 2, b.md: 1).
        assertThat(((Number) result.get("lines")).longValue()).isEqualTo(3L);
        assertThat(result).containsEntry("filesCounted", 2);
        // a.md has 3 lines, b.md has 1 line → 4 total scanned.
        assertThat(((Number) result.get("totalLinesScanned")).longValue()).isEqualTo(4L);
    }

    @Test
    void count_carriesReadOnlyLabel_only() {
        WorkspaceCountTool tool = new WorkspaceCountTool(workspace);
        assertThat(tool.labels()).containsExactly("read-only");
    }

}
