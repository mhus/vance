package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code client_file_delete}. The sandbox gate lives in
 * {@code ClientSecurityService} and is tested there — here only the
 * tool's own behaviour.
 */
class ClientFileDeleteToolTest {

    private final ClientFileDeleteTool tool = new ClientFileDeleteTool();
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-client-delete-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    @Test
    void delete_existingFile_removesItAndReportsDeleted() throws IOException {
        Path file = root.resolve("gone.txt");
        Files.writeString(file, "bye", StandardCharsets.UTF_8);

        Map<String, Object> out = tool.invoke(Map.of("path", file.toString()));

        assertThat(out).containsEntry("deleted", true);
        assertThat(out).containsEntry("path", file.toAbsolutePath().toString());
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void delete_missingFile_isNoOpNotAnError() {
        Path file = root.resolve("never-existed.txt");

        Map<String, Object> out = tool.invoke(Map.of("path", file.toString()));

        assertThat(out).containsEntry("deleted", false);
    }

    @Test
    void delete_directory_isRefused() throws IOException {
        Path dir = Files.createDirectory(root.resolve("sub"));
        Files.writeString(dir.resolve("inner.txt"), "x", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> tool.invoke(Map.of("path", dir.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directory");
        assertThat(Files.exists(dir)).isTrue();
    }

    @Test
    void delete_blankPath_isRejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("path", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void tool_isDeferred_soTheFileDeleteWrapperIsTheVisibleSurface() {
        assertThat(tool.primary()).isFalse();
        assertThat(tool.deferred()).isTrue();
    }
}
