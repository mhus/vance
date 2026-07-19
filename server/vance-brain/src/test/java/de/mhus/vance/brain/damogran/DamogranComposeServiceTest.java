package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DamogranComposeServiceTest {

    private WorkspaceService workspaceService;
    private WorkTargetService workTargetService;
    private DamogranTaskExecutor taskExecutor;
    private DamogranTransport transport;
    private DamogranComposeService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        workTargetService = mock(WorkTargetService.class);
        taskExecutor = mock(DamogranTaskExecutor.class);
        transport = mock(DamogranTransport.class);
        service = new DamogranComposeService(
                new DamogranManifestParser(), workspaceService, workTargetService, taskExecutor, transport);
    }

    private RootDirHandle handle(String label, String type) {
        return RootDirHandle.builder()
                .tenantId("t").projectId("p").dirName(label).type(type)
                .path(Path.of("/tmp/" + label))
                .descriptor(WorkspaceDescriptor.builder().dirName(label).label(label).type(type).build())
                .build();
    }

    private DamogranManifest manifest(String target, List<TaskSpec> tasks,
                                      List<ImportEntry> imports, List<ExportEntry> exports) {
        return new DamogranManifest(
                new WorkspaceSpec("ws", "temp", false, Map.of(), target), imports, tasks, exports, null, null);
    }

    private static TaskSpec task(String type) {
        return new TaskSpec(type, Map.of(), List.of());
    }

    @Test
    void run_allTasksSucceed_runsExportsAndReturnsSuccess() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of());
        when(workspaceService.createRootDir(any())).thenReturn(handle("ws", "temp"));
        when(taskExecutor.dispatch(any(), any())).thenReturn(DamogranTaskResult.success(List.of()));

        DamogranManifest m = manifest("WORK", List.of(task("exec")),
                List.of(new ImportEntry("vance:a.txt", "a.txt", java.util.Map.of())),
                List.of(new ExportEntry("out.txt", "vance:out.txt", java.util.Map.of())));

        DamogranComposeResult result = service.run("t", "p", "proc1", m);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.taskResults()).hasSize(1);
        verify(workTargetService).set(eq("proc1"), any());
        verify(transport).doImport(any(), any());
        verify(transport).doExport(any(), any());
    }

    @Test
    void run_taskFails_haltsAndSkipsExport() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of());
        when(workspaceService.createRootDir(any())).thenReturn(handle("ws", "temp"));
        when(taskExecutor.dispatch(any(), any())).thenReturn(DamogranTaskResult.failure("boom"));

        DamogranManifest m = manifest("WORK", List.of(task("exec"), task("llm")),
                List.of(), List.of(new ExportEntry("out.txt", "vance:out.txt", java.util.Map.of())));

        DamogranComposeResult result = service.run("t", "p", "proc1", m);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).isEqualTo("boom");
        assertThat(result.taskResults()).hasSize(1); // halted after the first task
        verify(transport, never()).doExport(any(), any());
    }

    @Test
    void run_nonWorkTarget_throws() {
        DamogranManifest m = manifest("CLIENT", List.of(), List.of(), List.of());

        assertThatThrownBy(() -> service.run("t", "p", "proc1", m))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("WORK only");
    }

    @Test
    void run_reusesExistingWorkspaceByLabel_doesNotCreate() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of(handle("ws", "temp")));
        when(taskExecutor.dispatch(any(), any())).thenReturn(DamogranTaskResult.success(List.of()));

        DamogranManifest m = manifest("WORK", List.of(task("exec")), List.of(), List.of());

        DamogranComposeResult result = service.run("t", "p", null, m);

        assertThat(result.isSuccess()).isTrue();
        verify(workspaceService, never()).createRootDir(any());
        verify(workTargetService, never()).set(any(), any()); // processId was null
    }
}
