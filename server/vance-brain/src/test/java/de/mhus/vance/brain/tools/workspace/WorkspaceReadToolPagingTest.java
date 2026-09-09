package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code work_file_read} routing between the whole-file read and the line
 * window. The two backends behind the {@code file_read} wrapper have to
 * offer the same parameters — a wrapper schema that only works on one
 * target is what sent a worker into a read-retry loop on 2026-08-11.
 */
class WorkspaceReadToolPagingTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";
    private static final String DIR = "scratch";
    private static final ToolInvocationContext CTX = new ToolInvocationContext(TENANT, PROJECT, "sess", "proc", "user");

    private WorkspaceService workspace;
    private WorkspaceReadTool tool;
    private java.nio.file.Path hashFile;

    @BeforeEach
    void setUp() throws Exception {
        workspace = mock(WorkspaceService.class);
        WorkspaceProperties properties = new WorkspaceProperties();
        tool = new WorkspaceReadTool(workspace, properties);
        when(workspace.read(any(), any(), any(), any(), anyInt()))
                .thenReturn(new WorkspaceService.ReadResult("whole", false, 5));
        when(workspace.readLines(any(), any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new WorkspaceService.ReadResult("window", false, 6));
        // The contentHash is computed from the resolved file, so the mock
        // needs a real one behind resolve() — same pattern as
        // WorkspaceReadOnlyToolsTest.
        hashFile = java.nio.file.Files.createTempFile("vance-ws-read-hash-", ".txt");
        when(workspace.resolve(any(), any(), any(), any())).thenReturn(hashFile);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        if (hashFile != null) {
            java.nio.file.Files.deleteIfExists(hashFile);
        }
    }

    @Test
    void everyReadCarriesTheWholeFileContentHash_notTheWindowHash() throws Exception {
        // The served content is the mocked window "whole"/"window" — the
        // contentHash must still describe the real file behind resolve(),
        // because the edit/write If-Match guard compares against that file.
        java.nio.file.Files.writeString(hashFile, "line1\nline2\n");

        Map<String, Object> unwindowed = tool.invoke(Map.of("path", "a.txt", "dirName", DIR), CTX);
        assertThat(unwindowed)
                .containsEntry("contentHash", de.mhus.vance.toolpack.core.ContentHashes.sha256Hex("line1\nline2\n"));

        Map<String, Object> windowed = tool.invoke(Map.of("path", "a.txt", "dirName", DIR, "startLine", 2), CTX);
        assertThat(windowed).containsEntry("content", "window");
        assertThat(windowed)
                .containsEntry("contentHash", de.mhus.vance.toolpack.core.ContentHashes.sha256Hex("line1\nline2\n"));
    }

    @Test
    void startLine_routesToTheLineWindow() {
        Map<String, Object> out =
                tool.invoke(Map.of("path", "src/Big.java", "dirName", DIR, "startLine", 300, "maxLines", 20), CTX);

        verify(workspace).readLines(eq(TENANT), eq(PROJECT), eq(DIR), eq("src/Big.java"), anyInt(), eq(300), eq(20));
        verify(workspace, never()).read(any(), any(), any(), any(), anyInt());
        assertThat(out).containsEntry("content", "window");
    }

    @Test
    void maxLinesAlone_isEnoughToPage() {
        tool.invoke(Map.of("path", "a.txt", "dirName", DIR, "maxLines", 10), CTX);

        // startLine omitted → 0 = "from the beginning", still a window read.
        verify(workspace).readLines(any(), any(), any(), any(), anyInt(), eq(0), eq(10));
    }

    @Test
    void withoutWindowParams_readsTheWholeFileCapped() {
        Map<String, Object> out = tool.invoke(Map.of("path", "a.txt", "dirName", DIR, "maxChars", 4096), CTX);

        verify(workspace).read(eq(TENANT), eq(PROJECT), eq(DIR), eq("a.txt"), eq(4096));
        verify(workspace, never()).readLines(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        assertThat(out).containsEntry("content", "whole");
    }

    @Test
    void nonPositiveWindowParams_countAsNotRequested() {
        tool.invoke(Map.of("path", "a.txt", "dirName", DIR, "startLine", 0, "maxLines", -5), CTX);

        // 0 / negative are what a model sends when it means "no window";
        // treating them as a window would return an empty result.
        verify(workspace).read(any(), any(), any(), any(), anyInt());
        verify(workspace, never()).readLines(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }
}
