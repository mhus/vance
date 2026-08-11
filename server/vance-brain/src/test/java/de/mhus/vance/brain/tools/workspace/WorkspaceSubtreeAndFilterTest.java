package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The parameters the WORK backends gained so that {@code file_list},
 * {@code file_find}, {@code file_grep} and {@code file_count} mean the same
 * thing on a workspace RootDir as they do on the user's machine: a
 * {@code path} subtree, a {@code maxDepth} cap, and the generated-content
 * filter with its {@code includeGenerated} escape.
 *
 * <p>Before this, {@code path} was declared by the wrapper and ignored here —
 * so {@code file_grep(path="src")} searched the whole RootDir and reported no
 * sign of it. {@code WorkTargetToolSymmetryTest} guards the schemas; these
 * tests guard that the schemas are not lying.
 */
class WorkspaceSubtreeAndFilterTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String DIR = "scratch";
    private static final ToolInvocationContext CTX =
            new ToolInvocationContext(TENANT, PROJECT, "sess", "proc", "user");

    private Path root;
    private WorkspaceService workspace;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-ws-subtree-test-");
        workspace = mock(WorkspaceService.class);
        when(workspace.resolve(eq(TENANT), eq(PROJECT), eq(DIR), any()))
                .thenAnswer(inv -> root.resolve((String) inv.getArgument(3)));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
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

    // ──────────────── file_list: one level, entries, path ────────────────

    @Test
    void list_returnsOneLevelWithDirectoryMarkers_notTheWholeTree() {
        mockList("README.md", "src/Main.java", "src/util/Helper.java", "pom.xml");

        Map<String, Object> out = new WorkspaceListTool(workspace)
                .invoke(Map.of("dirName", DIR), CTX);

        // Matches client_file_list: one level, directories marked. The old
        // behaviour returned all four paths under a 'files' key.
        assertThat(out.get("entries")).isEqualTo(List.of("README.md", "pom.xml", "src/"));
        assertThat(out).containsEntry("count", 3);
        assertThat(out).doesNotContainKey("files");
    }

    @Test
    void list_path_descendsIntoTheSubtree() {
        mockList("src/Main.java", "src/util/Helper.java", "other/x.txt");

        Map<String, Object> out = new WorkspaceListTool(workspace)
                .invoke(Map.of("dirName", DIR, "path", "src"), CTX);

        assertThat(out.get("entries")).isEqualTo(List.of("Main.java", "util/"));
    }

    @Test
    void list_path_doesNotMatchASiblingWithTheSamePrefix() {
        mockList("src/Main.java", "srcgen/Generated.java");

        Map<String, Object> out = new WorkspaceListTool(workspace)
                .invoke(Map.of("dirName", DIR, "path", "src"), CTX);

        // "src" must not swallow "srcgen/" — the prefix is slash-terminated.
        assertThat(out.get("entries")).isEqualTo(List.of("Main.java"));
    }

    // ──────────────── file_grep: path, maxDepth, includeGenerated ────────

    @Test
    void grep_path_limitsTheSearchToTheSubtree() throws IOException {
        writeFile("src/Main.java", "needle\n");
        writeFile("docs/notes.md", "needle\n");
        mockList("src/Main.java", "docs/notes.md");

        Map<String, Object> out = new WorkspaceGrepTool(workspace)
                .invoke(Map.of("pattern", "needle", "dirName", DIR, "path", "src"), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) out.get("matches");
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).containsEntry("path", "src/Main.java");
    }

    @Test
    void grep_skipsGeneratedDirectories_unlessAskedFor() throws IOException {
        writeFile("src/Main.java", "needle\n");
        writeFile("node_modules/dep/index.js", "needle\n");
        writeFile("target/classes/Main.class", "needle\n");
        mockList("src/Main.java", "node_modules/dep/index.js", "target/classes/Main.class");

        Map<String, Object> out = new WorkspaceGrepTool(workspace)
                .invoke(Map.of("pattern", "needle", "dirName", DIR), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) out.get("matches");
        assertThat(matches).hasSize(1);
        assertThat(out).containsEntry("generatedFilesSkipped", 2);
        // The result must say what it left out, or it reads as a full sweep.
        assertThat(out).containsKey("generatedFilesHint");

        Map<String, Object> all = new WorkspaceGrepTool(workspace)
                .invoke(Map.of("pattern", "needle", "dirName", DIR,
                        "includeGenerated", true), CTX);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allMatches = (List<Map<String, Object>>) all.get("matches");
        assertThat(allMatches).hasSize(3);
        assertThat(all).doesNotContainKey("generatedFilesSkipped");
    }

    @Test
    void grep_maxDepth_stopsAtTheGivenLevel() throws IOException {
        writeFile("top.txt", "needle\n");
        writeFile("a/mid.txt", "needle\n");
        writeFile("a/b/deep.txt", "needle\n");
        mockList("top.txt", "a/mid.txt", "a/b/deep.txt");

        Map<String, Object> out = new WorkspaceGrepTool(workspace)
                .invoke(Map.of("pattern", "needle", "dirName", DIR, "maxDepth", 1), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) out.get("matches");
        // maxDepth=1 means "flat directory", same as Files.walk(root, 1).
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).containsEntry("path", "top.txt");
    }

    // ──────────────── file_find: path + generated filter ─────────────────

    @Test
    void find_path_scopesTheWalk_andGlobIsRelativeToIt() throws IOException {
        writeFile("src/Main.java", "x");
        writeFile("src/util/Helper.java", "x");
        writeFile("docs/Main.java", "x");
        mockList("src/Main.java", "src/util/Helper.java", "docs/Main.java");

        Map<String, Object> out = new WorkspaceFindTool(workspace)
                .invoke(Map.of("dirName", DIR, "path", "src", "pathGlob", "util/*.java"), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        assertThat(entries).hasSize(1);
        // Returned paths stay RootDir-relative so they can be fed back into
        // file_read without the caller re-assembling them.
        assertThat(entries.get(0)).containsEntry("path", "src/util/Helper.java");
    }

    @Test
    void find_skipsGeneratedDirectories_byDefault() throws IOException {
        writeFile("src/Main.java", "x");
        writeFile("dist/bundle.js", "x");
        mockList("src/Main.java", "dist/bundle.js");

        Map<String, Object> out = new WorkspaceFindTool(workspace)
                .invoke(Map.of("dirName", DIR), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(out).containsEntry("generatedFilesSkipped", 1);
    }

    // ──────────────── file_count: directory path + filters ───────────────

    @Test
    void count_path_namingADirectory_countsItsSubtree() throws IOException {
        writeFile("src/a.txt", "one\ntwo\n");
        writeFile("src/deep/b.txt", "three\n");
        writeFile("other/c.txt", "four\nfive\nsix\n");
        mockList("src/a.txt", "src/deep/b.txt", "other/c.txt");

        Map<String, Object> out = new WorkspaceCountTool(workspace)
                .invoke(Map.of("dirName", DIR, "path", "src"), CTX);

        // Was file-only before: naming a directory counted nothing.
        assertThat(out).containsEntry("filesCounted", 2);
        assertThat(out).containsEntry("lines", 3L);
    }

    @Test
    void count_path_namingAFile_countsJustThatFile() throws IOException {
        writeFile("src/a.txt", "one\ntwo\n");
        mockList("src/a.txt");

        Map<String, Object> out = new WorkspaceCountTool(workspace)
                .invoke(Map.of("dirName", DIR, "path", "src/a.txt"), CTX);

        assertThat(out).containsEntry("filesCounted", 1);
        assertThat(out).containsEntry("lines", 2L);
    }

    @Test
    void count_withoutPath_countsTheWholeRootDir_minusGenerated() throws IOException {
        writeFile("a.txt", "one\n");
        writeFile("build/b.txt", "two\n");
        mockList("a.txt", "build/b.txt");

        Map<String, Object> out = new WorkspaceCountTool(workspace)
                .invoke(Map.of("dirName", DIR), CTX);

        assertThat(out).containsEntry("filesCounted", 1);
        assertThat(out).containsEntry("generatedFilesSkipped", 1);
    }
}
