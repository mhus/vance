package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.tools.ClientSecurityService;
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
 * The contract these tests defend: <em>a path a search tool emits can be
 * fed straight back into a read tool.</em>
 *
 * <p>It used to be violated. {@code client_file_grep} relativised hits
 * against its own walk root while {@code client_file_read} resolved a
 * relative path against the process working directory, so every follow-up
 * read of a hit outside the working directory landed on a path that did not
 * exist — with an error message ("Read failed: &lt;path&gt;") that gave the
 * caller nothing to correct.
 */
class ClientFilePathRoundTripTest {

    private Path root;
    private ClientSecurityService security;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-path-roundtrip-test-");
        security = mock(ClientSecurityService.class);
        when(security.permitWalkedFile(any())).thenReturn(true);
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
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    // ──────────────── the round trip ────────────────

    @Test
    void grepHitPath_canBeReadBack() throws IOException {
        writeFile("packages/components/src/target.ts", "export const marker = 1;\n");

        Map<String, Object> hits = new ClientFileGrepTool(security).invoke(Map.of(
                "pattern", "marker", "path", root.toString()));
        String hitPath = (String) rows(hits, "matches").get(0).get("path");

        Map<String, Object> read = new ClientFileReadTool().invoke(Map.of("path", hitPath));
        assertThat((String) read.get("content")).contains("marker");
    }

    @Test
    void findHitPath_canBeReadBack() throws IOException {
        writeFile("packages/components/src/target.ts", "export const marker = 1;\n");

        Map<String, Object> hits = new ClientFileFindTool(security).invoke(Map.of(
                "path", root.toString(), "pathGlob", "**/*.ts"));
        String hitPath = (String) rows(hits, "entries").get(0).get("path");

        Map<String, Object> read = new ClientFileReadTool().invoke(Map.of("path", hitPath));
        assertThat((String) read.get("content")).contains("marker");
    }

    @Test
    void toToolPath_belowWorkingDirectory_staysRelative() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertThat(ClientFilePaths.toToolPath(cwd.resolve("src/Main.java")))
                .isEqualTo("src/Main.java");
    }

    @Test
    void toToolPath_outsideWorkingDirectory_isAbsolute() {
        Path outside = root.resolve("x.txt");
        assertThat(ClientFilePaths.toToolPath(outside)).isEqualTo(outside.toString());
    }

    // ──────────────── actionable failures ────────────────

    @Test
    void read_missingFile_namesTheConditionAndTheWorkingDirectory() {
        String missing = root.resolve("nope.ts").toString();
        assertThatThrownBy(() -> new ClientFileReadTool().invoke(Map.of("path", missing)))
                .hasMessageContaining("No such file")
                .hasMessageContaining(Path.of("").toAbsolutePath().normalize().toString());
    }

    @Test
    void read_directory_pointsAtTheListTool() throws IOException {
        writeFile("dir/inner.txt", "x\n");
        assertThatThrownBy(() -> new ClientFileReadTool()
                .invoke(Map.of("path", root.resolve("dir").toString())))
                .hasMessageContaining("directory")
                .hasMessageContaining("client_file_list");
    }

    @Test
    void list_missingPath_saysMissingRatherThanNotADirectory() {
        String missing = root.resolve("nope").toString();
        assertThatThrownBy(() -> new ClientFileListTool().invoke(Map.of("path", missing)))
                .hasMessageContaining("No such file");
    }

    @Test
    void list_regularFile_pointsAtTheReadTool() throws IOException {
        writeFile("dir/inner.txt", "x\n");
        assertThatThrownBy(() -> new ClientFileListTool()
                .invoke(Map.of("path", root.resolve("dir/inner.txt").toString())))
                .hasMessageContaining("not a directory")
                .hasMessageContaining("client_file_read");
    }

    // ──────────────── generated-content filter ────────────────

    @Test
    void grep_skipsDependencyAndBuildDirectories_byDefault() throws IOException {
        writeFile("src/app.ts", "const marker = 1;\n");
        writeFile("node_modules/dep/index.js", "const marker = 2;\n");
        writeFile("packages/x/node_modules/dep/index.js", "const marker = 3;\n");
        writeFile("target/classes/Gen.java", "// marker\n");
        writeFile("dist/bundle.js", "var marker;\n");

        Map<String, Object> result = new ClientFileGrepTool(security).invoke(Map.of(
                "pattern", "marker", "path", root.toString()));

        assertThat(rows(result, "matches")).hasSize(1);
        assertThat((String) rows(result, "matches").get(0).get("path")).endsWith("src/app.ts");
        // The caller must be able to tell this was filtered, not exhaustive.
        assertThat(result).containsEntry("generatedFilesSkipped", 4);
    }

    @Test
    void grep_includeGenerated_searchesDependenciesOnPurpose() throws IOException {
        writeFile("src/app.ts", "const marker = 1;\n");
        writeFile("node_modules/dep/index.js", "const marker = 2;\n");

        Map<String, Object> result = new ClientFileGrepTool(security).invoke(Map.of(
                "pattern", "marker", "path", root.toString(),
                "includeGenerated", true));

        assertThat(rows(result, "matches")).hasSize(2);
        assertThat(result).doesNotContainKey("generatedFilesSkipped");
    }

    @Test
    void grep_pathPointingIntoADependency_isStillHonoured() throws IOException {
        writeFile("node_modules/dep/index.js", "const marker = 2;\n");

        Map<String, Object> result = new ClientFileGrepTool(security).invoke(Map.of(
                "pattern", "marker",
                "path", root.resolve("node_modules/dep/index.js").toString()));

        assertThat(rows(result, "matches")).hasSize(1);
    }

    @Test
    void find_skipsDependencyAndBuildDirectories_byDefault() throws IOException {
        writeFile("src/app.ts", "x\n");
        writeFile("node_modules/dep/index.ts", "y\n");

        Map<String, Object> result = new ClientFileFindTool(security).invoke(Map.of(
                "path", root.toString(), "pathGlob", "**/*.ts"));

        assertThat(rows(result, "entries")).hasSize(1);
        assertThat(result).containsEntry("generatedFilesSkipped", 1);
    }
}
