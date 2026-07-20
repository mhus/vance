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
import de.mhus.vance.brain.tools.exec.ExecManager;
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
        service = new DamogranComposeService(new DamogranManifestParser(), new ComposeRunRegistry(), List.of(
                new WorkspaceComposeRunner(workspaceService, workTargetService, taskExecutor, transport,
                        mock(ExecManager.class), mock(GitService.class))));
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
                new WorkspaceSpec("ws", "temp", false, false, Map.of(), target), imports, tasks, exports, null, null);
    }

    private DamogranManifest deleteManifest() {
        return new DamogranManifest(
                new WorkspaceSpec("ws", "temp", false, true, Map.of(), "WORK"),
                List.of(), List.of(), List.of(), null, null);
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
    void run_cancelRequested_haltsBeforeTaskWithoutDispatch() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of());
        when(workspaceService.createRootDir(any())).thenReturn(handle("ws", "temp"));
        WorkspaceComposeRunner runner = new WorkspaceComposeRunner(
                workspaceService, workTargetService, taskExecutor, transport,
                mock(ExecManager.class), mock(GitService.class));
        ComposeRun run = new ComposeRun("cr-x", "t", "p", "ws", java.time.Instant.EPOCH);
        run.requestCancel();

        DamogranManifest m = manifest("WORK", List.of(task("exec")), List.of(), List.of());
        DamogranComposeResult result = runner.run("t", "p", "proc1", m, null, run);

        assertThat(result.status()).isEqualTo(DamogranStatus.FAILURE);
        assertThat(result.error()).contains("cancelled");
        verify(taskExecutor, never()).dispatch(any(), any());
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
    void runAsync_completesInBackground_withRegisteredRunId() throws Exception {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of());
        when(workspaceService.createRootDir(any())).thenReturn(handle("ws", "temp"));
        when(taskExecutor.dispatch(any(), any())).thenReturn(DamogranTaskResult.success(List.of()));

        DamogranManifest m = manifest("WORK", List.of(task("exec")), List.of(), List.of());
        ComposeRun run = service.runAsync("t", "p", "proc1", m, null);

        assertThat(run.runId()).startsWith("cr-");
        assertThat(run.awaitDone(5000)).isTrue();
        assertThat(run.status()).isEqualTo(ComposeRun.Status.SUCCESS);
        assertThat(run.result()).isNotNull();
        assertThat(run.result().isSuccess()).isTrue();
    }

    @Test
    void run_targetWithoutRunner_throws() {
        // Only the WORK runner is registered here — CLIENT has no runner.
        DamogranManifest m = manifest("CLIENT", List.of(), List.of(), List.of());

        assertThatThrownBy(() -> service.run("t", "p", "proc1", m))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("not supported");
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

    @Test
    void run_deleteExistingWorkspace_disposesAndSucceedsWithoutProvisioning() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of(handle("ws", "temp")));

        DamogranComposeResult result = service.run("t", "p", "proc1", deleteManifest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.taskResults()).isEmpty();
        verify(workspaceService).disposeRootDir("t", "p", "ws");
        verify(workspaceService, never()).createRootDir(any());
        verify(workTargetService, never()).set(any(), any());
    }

    @Test
    void run_deleteMissingWorkspace_isNoOpSuccess() {
        when(workspaceService.listRootDirs("t", "p")).thenReturn(List.of());

        DamogranComposeResult result = service.run("t", "p", null, deleteManifest());

        assertThat(result.isSuccess()).isTrue();
        verify(workspaceService, never()).disposeRootDir(any(), any(), any());
        verify(workspaceService, never()).createRootDir(any());
    }
}
