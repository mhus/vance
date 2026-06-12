package de.mhus.vance.brain.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the {@link SessionIdleSweeper} only suspends sessions that
 * truly satisfy spec §7: idle for their own {@code idleTimeoutMs} and
 * no engine in {@code RUNNING}/{@code BLOCKED}/{@code PAUSED}. The
 * coarse Mongo pre-filter is mocked — we test the in-app branch.
 */
class SessionIdleSweeperTest {

    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private SessionLifecycleService lifecycleService;
    private de.mhus.vance.brain.execution.ExecutionRegistryService executionRegistryService;
    private SessionIdleSweeper sweeper;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        lifecycleService = mock(SessionLifecycleService.class);
        executionRegistryService = mock(de.mhus.vance.brain.execution.ExecutionRegistryService.class);
        sweeper = new SessionIdleSweeper(
                sessionService, thinkProcessService, lifecycleService,
                executionRegistryService);
        ReflectionTestUtils.setField(sweeper, "coarseCutoffSeconds", 30L);
    }

    @Test
    void sweep_emptyCandidates_isNoop() {
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of());

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    @Test
    void sweep_idleLongEnoughWithNoActiveEngine_suspendsAsIdle() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(process(ThinkProcessStatus.IDLE)));

        sweeper.sweep();

        verify(lifecycleService, times(1))
                .suspendCascade("s-1", SuspendCause.IDLE);
    }

    @Test
    void sweep_idleStillWithinTimeout_isNoop() {
        // 30s idle, threshold 60s — not yet eligible.
        SessionDocument s = idleCandidate("s-1", millisAgo(30_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
        // Per-session timeout check must short-circuit before the process
        // lookup — cheap.
        verify(thinkProcessService, never()).findBySession(any(), any());
    }

    @Test
    void sweep_idleLongEnoughButRunningEngine_isNoop() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(
                        process(ThinkProcessStatus.IDLE),
                        process(ThinkProcessStatus.RUNNING)));

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    @Test
    void sweep_blockedEngineCountsAsActive() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(process(ThinkProcessStatus.BLOCKED)));

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    @Test
    void sweep_pausedEngineCountsAsActive() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(process(ThinkProcessStatus.PAUSED)));

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    @Test
    void sweep_closedAndSuspendedAndInitDoNotBlockSuspend() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(
                        process(ThinkProcessStatus.CLOSED),
                        process(ThinkProcessStatus.SUSPENDED),
                        process(ThinkProcessStatus.INIT),
                        process(ThinkProcessStatus.IDLE)));

        sweeper.sweep();

        verify(lifecycleService, times(1))
                .suspendCascade("s-1", SuspendCause.IDLE);
    }

    @Test
    void sweep_failureForOneSessionContinuesWithNextCandidate() {
        SessionDocument bad = idleCandidate("s-bad", millisAgo(2L * 60_000L), 60_000L);
        SessionDocument good = idleCandidate("s-good", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(bad, good));
        when(thinkProcessService.findBySession(any(), any()))
                .thenReturn(List.of(process(ThinkProcessStatus.IDLE)));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(lifecycleService).suspendCascade(eq("s-bad"), any());

        sweeper.sweep();

        verify(lifecycleService).suspendCascade("s-good", SuspendCause.IDLE);
    }

    @Test
    void sweep_activeExecJobBlocksSuspendEvenWithoutActiveEngine() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(process(ThinkProcessStatus.IDLE)));
        when(executionRegistryService.hasActiveJobsForSession(eq("acme"), eq("s-1")))
                .thenReturn(true);

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    @Test
    void sweep_noActiveExecJob_allowsSuspendOnIdle() {
        SessionDocument s = idleCandidate("s-1", millisAgo(2L * 60_000L), 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(process(ThinkProcessStatus.IDLE)));
        when(executionRegistryService.hasActiveJobsForSession(eq("acme"), eq("s-1")))
                .thenReturn(false);

        sweeper.sweep();

        verify(lifecycleService, times(1))
                .suspendCascade("s-1", SuspendCause.IDLE);
    }

    @Test
    void sweep_nullLastActivity_neverSuspends() {
        SessionDocument s = idleCandidate("s-1", null, 60_000L);
        when(sessionService.findIdleCandidates(any())).thenReturn(List.of(s));

        sweeper.sweep();

        verify(lifecycleService, never()).suspendCascade(any(), any());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static SessionDocument idleCandidate(
            String sessionId, Instant lastActivityAt, long idleTimeoutMs) {
        SessionDocument s = new SessionDocument();
        s.setSessionId(sessionId);
        s.setTenantId("acme");
        s.setStatus(SessionStatus.IDLE);
        s.setOnIdle(IdlePolicy.SUSPEND);
        s.setIdleTimeoutMs(idleTimeoutMs);
        s.setLastActivityAt(lastActivityAt);
        return s;
    }

    private static Instant millisAgo(long ms) {
        return Instant.now().minusMillis(ms);
    }

    private static ThinkProcessDocument process(ThinkProcessStatus status) {
        return ThinkProcessDocument.builder()
                .id("p-" + status.name().toLowerCase())
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s-1")
                .status(status)
                .build();
    }
}
