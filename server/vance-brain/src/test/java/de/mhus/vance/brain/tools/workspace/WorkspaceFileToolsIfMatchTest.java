package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * If-Match guard on {@code work_file_edit} / {@code work_file_write}: a
 * stale {@code expectedContentHash} must refuse the change instead of
 * applying it to content the caller never saw, and a successful change
 * must report the {@code contentHash} the next call can chain on.
 *
 * <p>The {@link WorkspaceService} is mocked at its {@code read}/{@code write}/
 * {@code resolve} surface over a real temp directory — read/write operate on
 * real files, so the hashes describe actual bytes.
 */
class WorkspaceFileToolsIfMatchTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String DIR = "scratch";
    private static final ToolInvocationContext CTX = new ToolInvocationContext(TENANT, PROJECT, "sess", "proc", "user");

    private Path root;
    private WorkspaceService workspace;
    private WorkspaceEditTool editTool;
    private WorkspaceWriteTool writeTool;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("vance-ws-ifmatch-test-");
        workspace = mock(WorkspaceService.class);
        WorkspaceProperties properties = new WorkspaceProperties();
        editTool = new WorkspaceEditTool(workspace, properties);
        writeTool = new WorkspaceWriteTool(workspace);

        when(workspace.resolve(eq(TENANT), eq(PROJECT), eq(DIR), any()))
                .thenAnswer(inv -> root.resolve((String) inv.getArgument(3)));
        when(workspace.read(eq(TENANT), eq(PROJECT), eq(DIR), any(), anyInt())).thenAnswer(inv -> {
            Path p = root.resolve((String) inv.getArgument(3));
            String full = Files.readString(p, StandardCharsets.UTF_8);
            int cap = inv.getArgument(4);
            return full.length() > cap
                    ? new WorkspaceService.ReadResult(full.substring(0, cap), true, full.length())
                    : new WorkspaceService.ReadResult(full, false, full.length());
        });
        when(workspace.write(eq(TENANT), eq(PROJECT), eq(DIR), any(), any())).thenAnswer(inv -> {
            Path p = root.resolve((String) inv.getArgument(3));
            Files.createDirectories(p.getParent() == null ? root : p.getParent());
            Files.writeString(p, (String) inv.getArgument(4), StandardCharsets.UTF_8);
            return p;
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    private void writeFile(String relPath, String content) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ──────────────── work_file_edit ────────────────

    @Test
    void edit_matchingHash_appliesAndReturnsTheNewHash() throws IOException {
        writeFile("a.txt", "alpha\nbeta\ngamma\n");

        Map<String, Object> out = editTool.invoke(
                Map.of(
                        "path",
                        "a.txt",
                        "dirName",
                        DIR,
                        "oldText",
                        "beta",
                        "newText",
                        "BETA",
                        "expectedContentHash",
                        ContentHashes.sha256Hex("alpha\nbeta\ngamma\n")),
                CTX);

        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("alpha\nBETA\ngamma\n");
        assertThat(out).containsEntry("contentHash", ContentHashes.sha256Hex("alpha\nBETA\ngamma\n"));
    }

    @Test
    void edit_staleHash_refusesWithoutTouchingTheFile() throws IOException {
        writeFile("a.txt", "alpha\nbeta\ngamma\n");

        assertThatThrownBy(() -> editTool.invoke(
                        Map.of(
                                "path",
                                "a.txt",
                                "dirName",
                                DIR,
                                "oldText",
                                "beta",
                                "newText",
                                "BETA",
                                "expectedContentHash",
                                ContentHashes.sha256Hex("read long ago\n")),
                        CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contentHash mismatch")
                .hasMessageContaining("read the file again");
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("alpha\nbeta\ngamma\n");
        verify(workspace, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void edit_staleHash_beatsTheSnippetFailure() throws IOException {
        writeFile("a.txt", "alpha\n");

        // The hash check runs before the snippet match so the model gets
        // the right advice (re-read) instead of the wrong one (expand the
        // snippet context) when both would fail.
        assertThatThrownBy(() -> editTool.invoke(
                        Map.of(
                                "path",
                                "a.txt",
                                "dirName",
                                DIR,
                                "oldText",
                                "was-there-before",
                                "newText",
                                "x",
                                "expectedContentHash",
                                ContentHashes.sha256Hex("old\n")),
                        CTX))
                .hasMessageContaining("contentHash mismatch")
                .hasMessageNotContaining("oldText not found");
    }

    @Test
    void edit_chainedHashes_allowConsecutiveEditsWithoutRereading() throws IOException {
        writeFile("a.txt", "one\ntwo\nthree\n");

        Map<String, Object> first = editTool.invoke(
                Map.of(
                        "path",
                        "a.txt",
                        "dirName",
                        DIR,
                        "oldText",
                        "one",
                        "newText",
                        "ONE",
                        "expectedContentHash",
                        ContentHashes.sha256Hex("one\ntwo\nthree\n")),
                CTX);

        editTool.invoke(
                Map.of(
                        "path",
                        "a.txt",
                        "dirName",
                        DIR,
                        "oldText",
                        "three",
                        "newText",
                        "THREE",
                        "expectedContentHash",
                        first.get("contentHash")),
                CTX);

        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("ONE\ntwo\nTHREE\n");
    }

    // ──────────────── work_file_write ────────────────

    @Test
    void write_matchingHash_overwritesAndReturnsTheNewHash() throws IOException {
        writeFile("a.txt", "old\n");

        Map<String, Object> out = writeTool.invoke(
                Map.of(
                        "path",
                        "a.txt",
                        "dirName",
                        DIR,
                        "content",
                        "new\n",
                        "expectedContentHash",
                        ContentHashes.sha256Hex("old\n")),
                CTX);

        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("new\n");
        assertThat(out).containsEntry("contentHash", ContentHashes.sha256Hex("new\n"));
    }

    @Test
    void write_staleHash_refusesWithoutOverwriting() throws IOException {
        writeFile("a.txt", "current\n");

        assertThatThrownBy(() -> writeTool.invoke(
                        Map.of(
                                "path",
                                "a.txt",
                                "dirName",
                                DIR,
                                "content",
                                "new\n",
                                "expectedContentHash",
                                ContentHashes.sha256Hex("read long ago\n")),
                        CTX))
                .hasMessageContaining("contentHash mismatch")
                .hasMessageContaining("read the file again");
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("current\n");
        verify(workspace, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void write_vanishedFile_refusesInsteadOfResurrecting() throws IOException {
        String stale = ContentHashes.sha256Hex("was here\n");

        assertThatThrownBy(() -> writeTool.invoke(
                        Map.of("path", "gone.txt", "dirName", DIR, "content", "new\n", "expectedContentHash", stale),
                        CTX))
                .hasMessageContaining("no longer exists")
                .hasMessageContaining("re-check with work_file_read");
        assertThat(Files.exists(root.resolve("gone.txt"))).isFalse();
    }
}
