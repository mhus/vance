package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.execution.ExecutionOwner;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionScopeFilter;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.tools.exec.ExecLabels;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptRunAuthServiceTest {

    private ExecutionRegistryService registry;
    private ScriptRunAuthService service;

    @BeforeEach
    void setUp() {
        registry = mock(ExecutionRegistryService.class);
        service = new ScriptRunAuthService(registry);
    }

    @Test
    void accepts_loopbackPeerAndRunningEntry() {
        stubRegistry("run-1", "acme", "proj", ExecutionStatus.RUNNING);

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "run-1", "proj", null),
                request("127.0.0.1"));

        assertThat(ok).isTrue();
    }

    @Test
    void accepts_loopbackIpv6() {
        stubRegistry("run-1", "acme", "proj", ExecutionStatus.RUNNING);

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "run-1", "proj", null),
                request("::1"));

        assertThat(ok).isTrue();
    }

    @Test
    void rejects_nonLoopbackPeer() {
        stubRegistry("run-1", "acme", "proj", ExecutionStatus.RUNNING);

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "run-1", "proj", null),
                request("10.0.0.5"));

        assertThat(ok).isFalse();
    }

    @Test
    void rejects_unknownRunId() {
        when(registry.list(any(), any())).thenReturn(List.of());

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "ghost", "proj", null),
                request("127.0.0.1"));

        assertThat(ok).isFalse();
    }

    @Test
    void rejects_terminalStatus() {
        stubRegistry("run-1", "acme", "proj", ExecutionStatus.COMPLETED);

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "run-1", "proj", null),
                request("127.0.0.1"));

        assertThat(ok).isFalse();
    }

    @Test
    void rejects_projectMismatchEvenIfRunning() {
        stubRegistry("run-1", "acme", "proj-A", ExecutionStatus.RUNNING);

        boolean ok = service.isAcceptable(
                claims("alice", "acme", "run-1", "proj-B", null),
                request("127.0.0.1"));

        assertThat(ok).isFalse();
    }

    @Test
    void rejects_missingRunIdClaim() {
        boolean ok = service.isAcceptable(
                VanceJwtClaims.user("alice", "acme",
                        Instant.now(), Instant.now().plusSeconds(60),
                        TokenType.SCRIPT_RUN),
                request("127.0.0.1"));

        assertThat(ok).isFalse();
    }

    @Test
    void rejects_missingProjectClaim() {
        boolean ok = service.isAcceptable(
                new VanceJwtClaims("alice", "acme", Instant.now(),
                        Instant.now().plusSeconds(60),
                        TokenType.SCRIPT_RUN,
                        "run-1", null, null),
                request("127.0.0.1"));

        assertThat(ok).isFalse();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private void stubRegistry(String runId, String tenant, String project, ExecutionStatus status) {
        Instant now = Instant.now();
        ExecutionRegistryEntry entry = new ExecutionRegistryEntry(
                "job-" + runId, ExecutionOwner.Brain.INSTANCE,
                tenant, project, "sess", null,
                "true", "ws",
                now, now, status == ExecutionStatus.RUNNING ? null : now,
                status, status == ExecutionStatus.RUNNING ? null : 0,
                "stdout.log", "stderr.log",
                Map.of(ExecLabels.KEY_RUN_ID, runId));
        when(registry.list(any(ExecutionScopeFilter.class),
                any())).thenReturn(List.of(entry));
    }

    private static VanceJwtClaims claims(
            String user, String tenant, String runId, String project, String session) {
        return VanceJwtClaims.scriptRun(user, tenant,
                Instant.now(), Instant.now().plusSeconds(60),
                runId, project, session);
    }

    private static HttpServletRequest request(String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }
}
