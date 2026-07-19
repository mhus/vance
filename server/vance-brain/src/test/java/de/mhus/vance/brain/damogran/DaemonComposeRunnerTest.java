package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.daemon.DaemonRegistry;
import de.mhus.vance.brain.daemon.DaemonRegistry.DaemonKey;
import de.mhus.vance.brain.daemon.DaemonRegistry.DaemonRef;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class DaemonComposeRunnerTest {

    private WorkTargetService workTargetService;
    private ThinkProcessService thinkProcessService;
    private DaemonRegistry daemonRegistry;
    private DaemonComposeRunner runner;

    @BeforeEach
    void setUp() {
        workTargetService = mock(WorkTargetService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        daemonRegistry = mock(DaemonRegistry.class);
        runner = new DaemonComposeRunner(workTargetService, thinkProcessService,
                mock(ToolDispatcher.class), mock(DamogranTransport.class), daemonRegistry);
    }

    private DamogranManifest manifest(List<TaskSpec> tasks) {
        return new DamogranManifest(
                new WorkspaceSpec("build-box", "temp", false, false, Map.of(), "DAEMON"),
                List.of(), tasks, List.of(), null, null);
    }

    private ThinkProcessDocument process(String sessionId) {
        ThinkProcessDocument p = mock(ThinkProcessDocument.class);
        when(p.getSessionId()).thenReturn(sessionId);
        when(thinkProcessService.findById("proc")).thenReturn(Optional.of(p));
        return p;
    }

    private DaemonRef liveRef() {
        return new DaemonRef(new DaemonKey("t", "p", "build-box"),
                mock(WebSocketSession.class), Map.of(), Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void run_daemonNotConnected_throws() {
        process("s1");
        when(daemonRegistry.find("t", "p", "build-box")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.run("t", "p", "proc",
                manifest(List.of(new TaskSpec("exec", Map.of("command", "make"), List.of()))), null))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("build-box")
                .hasMessageContaining("not connected");
    }

    @Test
    void run_nonExecTask_failsAndHalts() {
        process("s1");
        when(daemonRegistry.find("t", "p", "build-box")).thenReturn(Optional.of(liveRef()));

        DamogranComposeResult result = runner.run("t", "p", "proc",
                manifest(List.of(new TaskSpec("python", Map.of("code", "x=1"), List.of()))), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("python").contains("DAEMON");
    }
}
