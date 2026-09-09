package de.mhus.vance.foot.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.core.ContentHashes;
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
 * Unit tests for {@code client_file_write}'s If-Match guard. The write
 * itself is trivial — the interesting part is that a stale expectation
 * never overwrites anything, and that a fresh write reports the
 * {@code contentHash} the next edit can chain on.
 */
class ClientFileWriteToolTest {

    private final ClientFileWriteTool tool = new ClientFileWriteTool();
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-client-write-test-");
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
    void write_matchingContentHash_overwritesAndReturnsTheNewHash() throws IOException {
        Path file = write("a.txt", "old\n");
        String hash = ContentHashes.sha256Hex("old\n");

        Map<String, Object> out =
                tool.invoke(Map.of("path", file.toString(), "content", "new\n", "expectedContentHash", hash));

        assertThat(Files.readString(file)).isEqualTo("new\n");
        assertThat(out).containsEntry("contentHash", ContentHashes.sha256Hex("new\n"));
    }

    @Test
    void write_withoutGuard_overwritesBlindly() throws IOException {
        Path file = write("a.txt", "old\n");

        tool.invoke(Map.of("path", file.toString(), "content", "new\n"));

        // The pre-If-Match behaviour is unchanged when the param is omitted.
        assertThat(Files.readString(file)).isEqualTo("new\n");
    }

    @Test
    void write_staleContentHash_refusesWithoutTouchingTheFile() throws IOException {
        Path file = write("a.txt", "current\n");
        String stale = ContentHashes.sha256Hex("read long ago\n");

        assertThatThrownBy(() ->
                        tool.invoke(Map.of("path", file.toString(), "content", "new\n", "expectedContentHash", stale)))
                .hasMessageContaining("contentHash mismatch")
                .hasMessageContaining("read the file again");
        assertThat(Files.readString(file)).isEqualTo("current\n");
    }

    @Test
    void write_vanishedFile_refusesInsteadOfResurrecting() throws IOException {
        Path gone = root.resolve("gone.txt");
        String stale = ContentHashes.sha256Hex("was here\n");

        assertThatThrownBy(() ->
                        tool.invoke(Map.of("path", gone.toString(), "content", "new\n", "expectedContentHash", stale)))
                .hasMessageContaining("no longer exists")
                .hasMessageContaining("re-check with client_file_read");
        assertThat(Files.exists(gone)).isFalse();
    }

    @Test
    void write_blankExpectedContentHash_isRejectedNotIgnored() throws IOException {
        Path file = write("a.txt", "old\n");

        assertThatThrownBy(() ->
                        tool.invoke(Map.of("path", file.toString(), "content", "new\n", "expectedContentHash", "")))
                .hasMessageContaining("'expectedContentHash' must be a non-empty string");
        assertThat(Files.readString(file)).isEqualTo("old\n");
    }
}
