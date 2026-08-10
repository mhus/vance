package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code client_file_edit}, with the emphasis on failure
 * messages: an edit that did not happen has to say <em>why</em> it did
 * not happen. The tool used to wrap every failure as
 * {@code "Edit failed: " + e.getMessage()}, which for a missing file
 * degenerates to the bare path — the model could not distinguish it
 * from a non-unique match and reported the edit as done.
 */
class ClientFileEditToolTest {

    private final ClientFileEditTool tool = new ClientFileEditTool();
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-client-edit-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    private Path write(String name, String content) throws IOException {
        Path p = root.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void edit_uniqueMatch_replacesAndReportsCounts() throws IOException {
        Path file = write("a.txt", "alpha\nbeta\ngamma\n");

        Map<String, Object> out = tool.invoke(Map.of(
                "path", file.toString(), "oldText", "beta", "newText", "BETA"));

        assertThat(Files.readString(file)).isEqualTo("alpha\nBETA\ngamma\n");
        assertThat(out).containsEntry("replaced", 1);
        assertThat(out).containsEntry("totalChars", "alpha\nBETA\ngamma\n".length());
    }

    @Test
    void edit_missingFile_namesTheMissingFileAndNotJustThePath() {
        Path missing = root.resolve("nope.vue");

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", missing.toString(), "oldText", "x", "newText", "y")))
                .hasMessageContaining("No such file")
                .hasMessageContaining(missing.toAbsolutePath().normalize().toString());
    }

    @Test
    void edit_snippetNotFound_saysSoAndKeepsFileUntouched() throws IOException {
        Path file = write("a.txt", "alpha\n");

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", file.toString(), "oldText", "beta", "newText", "BETA")))
                .hasMessageContaining("oldText not found")
                .hasMessageNotContaining("No such file");
        assertThat(Files.readString(file)).isEqualTo("alpha\n");
    }

    @Test
    void edit_snippetAmbiguous_saysSoAndKeepsFileUntouched() throws IOException {
        Path file = write("a.txt", "dup\nother\ndup\n");

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", file.toString(), "oldText", "dup", "newText", "X")))
                .hasMessageContaining("appears multiple times")
                .hasMessageContaining("unique");
        assertThat(Files.readString(file)).isEqualTo("dup\nother\ndup\n");
    }

    @Test
    void edit_pathIsDirectory_pointsAtTheRightProblem() {
        assertThatThrownBy(() -> tool.invoke(Map.of(
                "path", root.toString(), "oldText", "x", "newText", "y")))
                .hasMessageContaining("directory");
    }
}
