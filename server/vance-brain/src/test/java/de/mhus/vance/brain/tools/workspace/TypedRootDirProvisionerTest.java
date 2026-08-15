package de.mhus.vance.brain.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;

class TypedRootDirProvisionerTest {

    private WorkspaceService workspace;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        workspace = mock(WorkspaceService.class);
        ctx = mock(ToolInvocationContext.class);
        when(ctx.tenantId()).thenReturn("acme");
        when(ctx.projectId()).thenReturn("proj");
        when(ctx.processId()).thenReturn("proc-1");
        when(ctx.sessionId()).thenReturn("sess-1");
    }

    private static RootDirHandle handle(String dirName, String type, String label) {
        RootDirHandle h = mock(RootDirHandle.class);
        when(h.getDirName()).thenReturn(dirName);
        when(h.getType()).thenReturn(type);
        WorkspaceDescriptor d = mock(WorkspaceDescriptor.class);
        when(d.getLabel()).thenReturn(label);
        when(h.getDescriptor()).thenReturn(d);
        return h;
    }

    @Test
    void ensure_whenCanonicalDirExists_reusesItWithoutCreating() {
        RootDirHandle existing = handle("_python", PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL);
        when(workspace.listRootDirs("acme", "proj")).thenReturn(List.of(existing));

        RootDirHandle result = TypedRootDirProvisioner.ensure(
                workspace, ctx, PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL, Map.of());

        assertThat(result).isSameAs(existing);
        verify(workspace, never()).createRootDir(any());
    }

    @Test
    void ensure_whenAbsent_createsWithTypeLabelAndSurvivesCreatorClose() {
        when(workspace.listRootDirs("acme", "proj")).thenReturn(List.of());
        RootDirHandle created = handle("_python", PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL);
        when(workspace.createRootDir(any())).thenReturn(created);

        RootDirHandle result = TypedRootDirProvisioner.ensure(
                workspace, ctx, PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL,
                Map.of(PythonHandler.META_PYTHON_PATH, "python3"));

        assertThat(result).isSameAs(created);
        ArgumentCaptor<RootDirSpec> spec = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspace).createRootDir(spec.capture());
        assertThat(spec.getValue().getType()).isEqualTo(PythonHandler.TYPE);
        assertThat(spec.getValue().getLabelHint()).isEqualTo(PythonHandler.DEFAULT_LABEL);
        // A venv is expensive to rebuild — the canonical workspace is per
        // project, not per turn.
        assertThat(spec.getValue().isDeleteOnCreatorClose()).isFalse();
        assertThat(spec.getValue().getMetadata())
                .containsEntry(PythonHandler.META_PYTHON_PATH, "python3");
    }

    @Test
    void ensure_ignoresRightLabelWithWrongType() {
        // A temp dir that happens to carry the label must not be mistaken
        // for the python workspace — that is the very confusion this
        // provisioner exists to end.
        RootDirHandle wrongType = handle("tmp", "temp", PythonHandler.DEFAULT_LABEL);
        when(workspace.listRootDirs("acme", "proj")).thenReturn(List.of(wrongType));
        RootDirHandle created = handle("_python", PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL);
        when(workspace.createRootDir(any())).thenReturn(created);

        RootDirHandle result = TypedRootDirProvisioner.ensure(
                workspace, ctx, PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL, Map.of());

        assertThat(result).isSameAs(created);
    }

    @Test
    void ensure_ignoresRightTypeWithWrongLabel() {
        // A user-created python workspace stays the user's; the canonical
        // one is identified by label so the two never collide.
        RootDirHandle userDir = handle("mine", PythonHandler.TYPE, "my-analysis");
        when(workspace.listRootDirs("acme", "proj")).thenReturn(List.of(userDir));
        RootDirHandle created = handle("_python", PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL);
        when(workspace.createRootDir(any())).thenReturn(created);

        RootDirHandle result = TypedRootDirProvisioner.ensure(
                workspace, ctx, PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL, Map.of());

        assertThat(result).isSameAs(created);
    }

    @Test
    void ensure_withoutProjectScope_fails() {
        when(ctx.projectId()).thenReturn("");

        assertThatThrownBy(() -> TypedRootDirProvisioner.ensure(
                workspace, ctx, PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL, Map.of()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant and project scope");
    }

    @Test
    void find_returnsNullWhenNothingMatches() {
        // Build the handles before stubbing: creating a mock inside a
        // when(...) argument list nests stubbing and Mockito rejects it.
        RootDirHandle temp = handle("tmp", "temp", "tmp");
        RootDirHandle other = handle("mine", PythonHandler.TYPE, "my-analysis");
        when(workspace.listRootDirs("acme", "proj")).thenReturn(List.of(temp, other));

        assertThat(TypedRootDirProvisioner.find(
                workspace, "acme", "proj", PythonHandler.TYPE, PythonHandler.DEFAULT_LABEL))
                .isNull();
    }
}
