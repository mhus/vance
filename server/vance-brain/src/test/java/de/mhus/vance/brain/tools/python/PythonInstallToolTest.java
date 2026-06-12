package de.mhus.vance.brain.tools.python;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PythonInstallToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "instant-hole", "sess", "proc", "user");

    private WorkspaceService workspace;
    private ExecManager execManager;
    private ExecProperties properties;
    private PythonInstallTool tool;

    @BeforeEach
    void setUp() {
        workspace = mock(WorkspaceService.class);
        execManager = mock(ExecManager.class);
        properties = new ExecProperties();
        tool = new PythonInstallTool(workspace, execManager, properties);

        // Default: working RootDir resolves to "python" (a python-type RootDir).
        when(workspace.getWorkingDir(eq("acme"), eq("instant-hole"), eq("proc")))
                .thenReturn(Optional.of("python"));
        when(workspace.getRootDir(eq("acme"), eq("instant-hole"), eq("python")))
                .thenReturn(Optional.of(pythonHandle("python")));
        when(execManager.submitTrackedAndRender(
                anyString(), anyString(), any(), any(), anyString(), anyString(), anyLong(),
                any(de.mhus.vance.brain.tools.exec.SubmitOptions.class)))
                .thenReturn(Map.of("status", "COMPLETED", "exitCode", 0));
    }

    @Test
    void invoke_singlePackage_buildsPipInstallCommand() {
        tool.invoke(Map.of("package", "flask"), CTX);

        String cmd = capturedCommand();
        assertThat(cmd)
                .startsWith(".venv/bin/python -m pip install 'flask'")
                .contains("&& .venv/bin/python -m pip freeze > requirements.txt");
    }

    @Test
    void invoke_multiplePackages_buildsSinglePipCallWithAll() {
        tool.invoke(
                Map.of("packages", List.of("flask==3.0", "requests>=2", "numpy")),
                CTX);

        String cmd = capturedCommand();
        assertThat(cmd)
                .contains(".venv/bin/python -m pip install "
                        + "'flask==3.0' 'requests>=2' 'numpy'")
                .contains("pip freeze > requirements.txt");
    }

    @Test
    void invoke_packageAndPackages_combinesBoth() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("package", "flask");
        params.put("packages", List.of("requests", "numpy"));

        tool.invoke(params, CTX);

        String cmd = capturedCommand();
        assertThat(cmd).contains("'flask' 'requests' 'numpy'");
    }

    @Test
    void invoke_neither_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'package'")
                .hasMessageContaining("'packages'");
    }

    @Test
    void invoke_emptyPackagesList_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("packages", List.of()), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("python_install");
    }

    @Test
    void invoke_flagsAppendedVerbatim() {
        tool.invoke(
                Map.of("package", "flask", "flags", "--upgrade --no-deps"),
                CTX);

        String cmd = capturedCommand();
        assertThat(cmd)
                .contains("'flask' --upgrade --no-deps")
                .contains("pip freeze");
    }

    @Test
    void invoke_rejectsNonPythonRootDir() {
        when(workspace.getRootDir(eq("acme"), eq("instant-hole"), eq("python")))
                .thenReturn(Optional.of(gitHandle("python")));

        assertThatThrownBy(() -> tool.invoke(Map.of("package", "flask"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("expected 'python'");
    }

    private String capturedCommand() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(execManager, times(1)).submitTrackedAndRender(
                eq("acme"), eq("instant-hole"),
                eq("sess"), eq("proc"),
                eq("python"), cmd.capture(), anyLong(),
                any(de.mhus.vance.brain.tools.exec.SubmitOptions.class));
        return cmd.getValue();
    }

    private static RootDirHandle pythonHandle(String dirName) {
        return rootDir(dirName, PythonHandler.TYPE);
    }

    private static RootDirHandle gitHandle(String dirName) {
        return rootDir(dirName, "git");
    }

    private static RootDirHandle rootDir(String dirName, String type) {
        WorkspaceDescriptor d = WorkspaceDescriptor.builder()
                .tenant("acme")
                .projectId("instant-hole")
                .dirName(dirName)
                .label(dirName)
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
