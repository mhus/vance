package de.mhus.vance.brain.tools.python;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PythonCreateToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "instant-hole", "sess", "proc", "user");

    private WorkspaceService workspace;
    private PythonCreateTool tool;

    @BeforeEach
    void setUp() {
        workspace = mock(WorkspaceService.class);
        tool = new PythonCreateTool(workspace);
    }

    @Test
    void invoke_returnsExistingRootDir_whenLabelMatches() {
        RootDirHandle existing = handle("python", "python");
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of(existing));

        Map<String, Object> out = tool.invoke(Map.of(), CTX);

        assertThat(out).containsEntry("status", "exists");
        assertThat(out).containsEntry("dirName", "python");
        verify(workspace, never()).createRootDir(any());
    }

    @Test
    void invoke_createsFreshRootDir_whenNoneMatches() {
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of());
        RootDirHandle fresh = handle("python", "python");
        when(workspace.createRootDir(any(RootDirSpec.class))).thenReturn(fresh);

        Map<String, Object> out = tool.invoke(Map.of(), CTX);

        assertThat(out).containsEntry("status", "created");
        assertThat(out).containsEntry("dirName", "python");
        verify(workspace, times(1)).createRootDir(any());
    }

    @Test
    void invoke_customLabel_findsExistingByLabel() {
        RootDirHandle other = handle("analysis-env", "analysis-env");
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of(other));

        Map<String, Object> out = tool.invoke(
                Map.of("label", "analysis-env"), CTX);

        assertThat(out).containsEntry("status", "exists");
        assertThat(out).containsEntry("dirName", "analysis-env");
        verify(workspace, never()).createRootDir(any());
    }

    @Test
    void invoke_customLabel_createsWhenOnlyOtherLabelsExist() {
        RootDirHandle differentLabel = handle("python", "python");
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of(differentLabel));
        RootDirHandle fresh = handle("analysis-env", "analysis-env");
        when(workspace.createRootDir(any(RootDirSpec.class))).thenReturn(fresh);

        Map<String, Object> out = tool.invoke(
                Map.of("label", "analysis-env"), CTX);

        assertThat(out).containsEntry("status", "created");
        assertThat(out).containsEntry("dirName", "analysis-env");
    }

    @Test
    void invoke_ignoresNonPythonRootDirsWhenChecking() {
        // A git-type RootDir with label "python" should not satisfy the
        // python_create idempotency check — different handler.
        RootDirHandle gitMisleading = handleOfType("python", "python", "git");
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of(gitMisleading));
        RootDirHandle fresh = handle("python", "python");
        when(workspace.createRootDir(any(RootDirSpec.class))).thenReturn(fresh);

        Map<String, Object> out = tool.invoke(Map.of(), CTX);

        assertThat(out).containsEntry("status", "created");
        verify(workspace, times(1)).createRootDir(any());
    }

    @Test
    void invoke_existingRootDir_promotesToWorkingDirWhenRequested() {
        RootDirHandle existing = handle("python", "python");
        when(workspace.listRootDirs(eq("acme"), eq("instant-hole")))
                .thenReturn(List.of(existing));

        tool.invoke(Map.of("asWorkingDir", true), CTX);

        verify(workspace, times(1))
                .setWorkingDir("acme", "instant-hole", "proc", "python");
        verify(workspace, never()).createRootDir(any());
    }

    private static RootDirHandle handle(String dirName, String label) {
        return handleOfType(dirName, label, PythonHandler.TYPE);
    }

    private static RootDirHandle handleOfType(String dirName, String label, String type) {
        WorkspaceDescriptor d = WorkspaceDescriptor.builder()
                .tenant("acme")
                .projectId("instant-hole")
                .dirName(dirName)
                .label(label)
                .type(type)
                .creatorProcessId("proc")
                .createdAt("2026-05-12T10:00:00Z")
                .deleteOnCreatorClose(false)
                .build();
        return RootDirHandle.builder()
                .tenantId("acme")
                .projectId("instant-hole")
                .dirName(dirName)
                .type(type)
                .path(Path.of("/tmp", dirName))
                .descriptor(d)
                .build();
    }
}
