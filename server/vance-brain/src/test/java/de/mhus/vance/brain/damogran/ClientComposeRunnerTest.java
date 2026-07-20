package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientComposeRunnerTest {

    private WorkTargetService workTargetService;
    private ThinkProcessService thinkProcessService;
    private DamogranTransport transport;
    private ClientComposeRunner runner;

    @BeforeEach
    void setUp() {
        workTargetService = mock(WorkTargetService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        transport = mock(DamogranTransport.class);
        runner = new ClientComposeRunner(
                workTargetService, thinkProcessService, mock(ToolDispatcher.class), transport);
    }

    private DamogranManifest manifest(
            List<TaskSpec> tasks, List<ImportEntry> imports, List<ExportEntry> exports, boolean delete) {
        return new DamogranManifest(
                new WorkspaceSpec("job", "temp", false, delete, Map.of(), "CLIENT"),
                imports, tasks, exports, null, null);
    }

    private static TaskSpec exec(String command) {
        return new TaskSpec("exec", Map.of("command", command), List.of());
    }

    @Test
    void run_import_dispatchedToTransport() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getSessionId()).thenReturn("s1");
        when(thinkProcessService.findById("proc")).thenReturn(Optional.of(process));
        when(workTargetService.clientConnected("s1")).thenReturn(true);

        // Import + no tasks: import flows to the transport (RemoteFileIo backend),
        // then the run succeeds.
        DamogranManifest m = manifest(List.of(),
                List.of(new ImportEntry("vance:a.txt", "a.txt", Map.of())), List.of(), false);

        DamogranComposeResult result = runner.run("t", "p", "proc", m, null);

        assertThat(result.isSuccess()).isTrue();
        org.mockito.Mockito.verify(transport)
                .doImport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void run_gitImport_withCredentialAlias_rejectedAsWorkOnly_andNotSentToTransport() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getSessionId()).thenReturn("s1");
        when(thinkProcessService.findById("proc")).thenReturn(Optional.of(process));
        when(workTargetService.clientConnected("s1")).thenReturn(true);

        // git:* is handled via remote exec, not the transport — a vault-backed
        // credentialAlias has no meaning there and must be rejected clearly.
        DamogranManifest m = manifest(List.of(),
                List.of(new ImportEntry("git:https://example.com/r.git", "repo",
                        Map.of("credentialAlias", "gh"))),
                List.of(), false);

        assertThatThrownBy(() -> runner.run("t", "p", "proc", m, null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("credentialAlias");
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.never())
                .doImport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void run_withDelete_throws_noManagedWorkspace() {
        DamogranManifest m = manifest(List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> runner.run("t", "p", "proc", m, null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("managed workspace");
    }

    @Test
    void run_withoutProcess_throws_needsSession() {
        DamogranManifest m = manifest(List.of(exec("ls")), List.of(), List.of(), false);

        assertThatThrownBy(() -> runner.run("t", "p", null, m, null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("session-bound process");
    }

    @Test
    void run_footNotConnected_throws() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getSessionId()).thenReturn("s1");
        when(thinkProcessService.findById("proc")).thenReturn(Optional.of(process));
        when(workTargetService.clientConnected("s1")).thenReturn(false);

        DamogranManifest m = manifest(List.of(exec("ls")), List.of(), List.of(), false);

        assertThatThrownBy(() -> runner.run("t", "p", "proc", m, null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("no Foot");
    }

    @Test
    void run_nonExecTask_failsAndHalts() {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getSessionId()).thenReturn("s1");
        when(thinkProcessService.findById("proc")).thenReturn(Optional.of(process));
        when(workTargetService.clientConnected("s1")).thenReturn(true);

        DamogranManifest m = manifest(
                List.of(new TaskSpec("python", Map.of("code", "x=1"), List.of())),
                List.of(), List.of(), false);

        DamogranComposeResult result = runner.run("t", "p", "proc", m, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("python").contains("not supported");
    }
}
