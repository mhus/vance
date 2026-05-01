package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for the cascade contracts on {@link SessionLifecycleService}:
 * onDisconnect policy dispatch, terminal-state skip, end-of-cascade
 * session transition. Uses a real {@link LaneScheduler} so cascade
 * futures actually complete; everything else is mocked.
 */
class SessionLifecycleServiceTest {

    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private ThinkEngineService engineService;
    private LaneScheduler laneScheduler;
    private SessionLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        engineService = mock(ThinkEngineService.class);
        laneScheduler = new LaneScheduler();

        @SuppressWarnings("unchecked")
        ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(engineService);

        lifecycle = new SessionLifecycleService(
                sessionService, thinkProcessService, engineProvider, laneScheduler);
        // Default forced-suspend floor — value doesn't matter for these tests.
        ReflectionTestUtils.setField(lifecycle, "forcedFloorMs", 1000L);
    }

    @AfterEach
    void tearDown() {
        // LaneScheduler.shutdown is package-private (PreDestroy); reflection
        // is the only way to call it from a different package.
        ReflectionTestUtils.invokeMethod(laneScheduler, "shutdown");
    }

    // ─── onDisconnect policy dispatch ───────────────────────────────────

    @Test
    void onDisconnect_suspendPolicy_runsSuspendCascade() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.SUSPEND);
        when(thinkProcessService.findBySession(any(), eq("s-1"))).thenReturn(List.of());

        lifecycle.onDisconnect("s-1");

        verify(sessionService).suspend(eq("s-1"), eq(SuspendCause.DISCONNECT), anyLong());
        verify(sessionService, never()).close(any());
    }

    @Test
    void onDisconnect_closePolicy_runsCloseCascade() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.CLOSE);
        when(thinkProcessService.findBySession(any(), eq("s-1"))).thenReturn(List.of());

        lifecycle.onDisconnect("s-1");

        verify(sessionService).close("s-1");
        verify(sessionService, never()).suspend(any(), any(), anyLong());
    }

    @Test
    void onDisconnect_keepOpenPolicy_isNoop() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.KEEP_OPEN);

        lifecycle.onDisconnect("s-1");

        verify(sessionService, never()).suspend(any(), any(), anyLong());
        verify(sessionService, never()).close(any());
        // Doesn't even fetch processes — early branch in switch.
        verify(thinkProcessService, never()).findBySession(any(), any());
    }

    @Test
    void onDisconnect_nullPolicy_defaultsToKeepOpen() {
        stubSession("s-1", SessionStatus.RUNNING, null);

        lifecycle.onDisconnect("s-1");

        verify(sessionService, never()).suspend(any(), any(), anyLong());
        verify(sessionService, never()).close(any());
    }

    @Test
    void onDisconnect_alreadyClosedSession_isNoop() {
        stubSession("s-1", SessionStatus.CLOSED, DisconnectPolicy.SUSPEND);

        lifecycle.onDisconnect("s-1");

        verify(sessionService, never()).suspend(any(), any(), anyLong());
        verify(sessionService, never()).close(any());
    }

    @Test
    void onDisconnect_alreadySuspendedSession_isNoop() {
        stubSession("s-1", SessionStatus.SUSPENDED, DisconnectPolicy.SUSPEND);

        lifecycle.onDisconnect("s-1");

        verify(sessionService, never()).suspend(any(), any(), anyLong());
    }

    @Test
    void onDisconnect_unknownSession_isNoop() {
        when(sessionService.findBySessionId("ghost")).thenReturn(Optional.empty());

        lifecycle.onDisconnect("ghost");

        verify(sessionService, never()).suspend(any(), any(), anyLong());
        verify(sessionService, never()).close(any());
    }

    // ─── suspendCascade ─────────────────────────────────────────────────

    @Test
    void suspendCascade_suspendsAllNonTerminalProcesses_thenSession() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.SUSPEND);
        ThinkProcessDocument p1 = process("p-1", ThinkProcessStatus.RUNNING);
        ThinkProcessDocument p2 = process("p-2", ThinkProcessStatus.IDLE);
        ThinkProcessDocument p3Closed = process("p-3", ThinkProcessStatus.CLOSED);
        ThinkProcessDocument p4Suspended = process("p-4", ThinkProcessStatus.SUSPENDED);
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(p1, p2, p3Closed, p4Suspended));

        lifecycle.suspendCascade("s-1", SuspendCause.IDLE);

        // Only the non-terminal ones go through engine.suspend.
        verify(engineService, times(1)).suspend(p1);
        verify(engineService, times(1)).suspend(p2);
        verify(engineService, never()).suspend(p3Closed);
        verify(engineService, never()).suspend(p4Suspended);
        // Session-level suspend fires after the cascade.
        verify(sessionService).suspend(eq("s-1"), eq(SuspendCause.IDLE), anyLong());
    }

    @Test
    void suspendCascade_engineFailure_fallsBackToServiceUpdate() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.SUSPEND);
        ThinkProcessDocument p1 = process("p-1", ThinkProcessStatus.RUNNING);
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(p1));
        // engine.suspend throws — service must fall back to direct status flip.
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(engineService).suspend(p1);

        lifecycle.suspendCascade("s-1", SuspendCause.DISCONNECT);

        verify(thinkProcessService).updateStatus("p-1", ThinkProcessStatus.SUSPENDED);
        verify(sessionService, atLeast(1))
                .suspend(eq("s-1"), eq(SuspendCause.DISCONNECT), anyLong());
    }

    @Test
    void suspendCascade_alreadySuspended_isNoop() {
        stubSession("s-1", SessionStatus.SUSPENDED, DisconnectPolicy.SUSPEND);

        lifecycle.suspendCascade("s-1", SuspendCause.DISCONNECT);

        verify(thinkProcessService, never()).findBySession(any(), any());
        verify(sessionService, never()).suspend(any(), any(), anyLong());
    }

    // ─── closeWithCascade ───────────────────────────────────────────────

    @Test
    void closeWithCascade_stopsAllNonClosedProcesses_thenSession() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.CLOSE);
        ThinkProcessDocument p1 = process("p-1", ThinkProcessStatus.RUNNING);
        ThinkProcessDocument p2Suspended = process("p-2", ThinkProcessStatus.SUSPENDED);
        ThinkProcessDocument p3Closed = process("p-3", ThinkProcessStatus.CLOSED);
        when(thinkProcessService.findBySession(any(), eq("s-1")))
                .thenReturn(List.of(p1, p2Suspended, p3Closed));

        lifecycle.closeWithCascade("s-1");

        // p3 already CLOSED → skipped. SUSPENDED is not terminal-for-close.
        verify(engineService, times(1)).stop(p1);
        verify(engineService, times(1)).stop(p2Suspended);
        verify(engineService, never()).stop(p3Closed);
        verify(sessionService).close("s-1");
    }

    @Test
    void closeWithCascade_engineFailure_fallsBackToCloseProcess() {
        stubSession("s-1", SessionStatus.RUNNING, DisconnectPolicy.CLOSE);
        ThinkProcessDocument p1 = process("p-1", ThinkProcessStatus.RUNNING);
        when(thinkProcessService.findBySession(any(), eq("s-1"))).thenReturn(List.of(p1));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(engineService).stop(p1);

        lifecycle.closeWithCascade("s-1");

        verify(thinkProcessService).closeProcess("p-1", CloseReason.STOPPED);
        verify(sessionService).close("s-1");
    }

    @Test
    void closeWithCascade_alreadyClosed_isNoop() {
        stubSession("s-1", SessionStatus.CLOSED, DisconnectPolicy.CLOSE);

        lifecycle.closeWithCascade("s-1");

        verify(thinkProcessService, never()).findBySession(any(), any());
        verify(sessionService, never()).close(any());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private void stubSession(String sessionId, SessionStatus status,
                             DisconnectPolicy policy) {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId(sessionId);
        doc.setTenantId("acme");
        doc.setStatus(status);
        doc.setOnDisconnect(policy);
        when(sessionService.findBySessionId(sessionId)).thenReturn(Optional.of(doc));
    }

    private static ThinkProcessDocument process(String id, ThinkProcessStatus status) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s-1")
                .status(status)
                .build();
    }
}
