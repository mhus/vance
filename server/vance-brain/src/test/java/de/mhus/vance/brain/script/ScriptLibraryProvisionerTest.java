package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.NodeHandler;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptLibraryProvisionerTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-1", "sess-1", "proc-1", null);

    @TempDir
    Path root;

    private WorkspaceService workspace;
    private NodeHandler nodeHandler;
    private RootDirHandle handle;
    private ScriptLibraryProvisioner provisioner;

    @BeforeEach
    void setUp() {
        workspace = mock(WorkspaceService.class);
        nodeHandler = mock(NodeHandler.class);
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor();
        descriptor.setLabel(NodeHandler.DEFAULT_LABEL);
        handle = RootDirHandle.builder()
                .tenantId("acme")
                .projectId("proj-1")
                .dirName(NodeHandler.DEFAULT_LABEL)
                .type(NodeHandler.TYPE)
                .path(root)
                .descriptor(descriptor)
                .build();
        when(workspace.listRootDirs("acme", "proj-1")).thenReturn(List.of(handle));
        when(workspace.withLabelLock(anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(3).get());
        provisioner = new ScriptLibraryProvisioner(workspace, nodeHandler);
    }

    @Test
    void ensure_missingPackage_isInstalledWithItsSpec() {
        Path result = provisioner.ensure(CTX, NodeHandler.DEFAULT_LABEL, Set.of("lodash@^4"));

        assertThat(result).isEqualTo(root.toAbsolutePath().normalize());
        verify(nodeHandler).install(handle, "lodash@^4");
    }

    @Test
    void ensure_installedPackage_isNotInstalledAgain() throws IOException {
        Path pkg = Files.createDirectories(root.resolve("node_modules").resolve("lodash"));
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"lodash\"}");

        provisioner.ensure(CTX, NodeHandler.DEFAULT_LABEL, Set.of("lodash@^4"));

        verify(nodeHandler, never()).install(any(), anyString());
    }

    @Test
    void ensure_nonRegistrySpec_isRejectedWithoutInstall() {
        assertThatThrownBy(() -> provisioner.ensure(CTX, NodeHandler.DEFAULT_LABEL, Set.of("file:/etc")))
                .isInstanceOf(WorkspaceException.class);

        verify(nodeHandler, never()).install(any(), anyString());
    }

    @Test
    void ensure_runsUnderTheRootDirLabelLock() {
        provisioner.ensure(CTX, NodeHandler.DEFAULT_LABEL, Set.of("lodash"));

        verify(workspace).withLabelLock(eq("acme"), eq("proj-1"), eq(NodeHandler.DEFAULT_LABEL), any());
    }
}
