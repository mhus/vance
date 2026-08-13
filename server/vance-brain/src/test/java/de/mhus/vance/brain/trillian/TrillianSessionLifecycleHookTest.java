package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The worker session has to follow its control session, or a deleted
 * control leaves a closed shell behind — invisible, because the worker
 * session is {@code system=true}, and one more per Trillian start.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrillianSessionLifecycleHookTest {

    private static final String TENANT = "acme";
    private static final String CONTROL = "sess-control";
    private static final String PEER = "sess-worker";
    private static final String ACCOUNT = "_trillian-04801";

    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    SessionLifecycleService lifecycleService;
    @Mock
    ObjectProvider<SessionLifecycleService> lifecycleProvider;
    @Mock
    UserService userService;
    @Mock
    de.mhus.vance.shared.session.SessionService sessionService;
    @Mock
    PermissionBootstrap permissionBootstrap;
    @Mock
    ObjectProvider<PermissionBootstrap> permissionProvider;

    TrillianSessionLifecycleHook hook;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(lifecycleProvider.getObject()).thenReturn(lifecycleService);
        org.mockito.Mockito.doAnswer(inv -> {
            ((java.util.function.Consumer<PermissionBootstrap>) inv.getArgument(0))
                    .accept(permissionBootstrap);
            return null;
        }).when(permissionProvider).ifAvailable(any());
        when(sessionService.findBySessionId(any()))
                .thenAnswer(inv -> java.util.Optional.of(session(inv.getArgument(0))));
        hook = new TrillianSessionLifecycleHook(
                thinkProcessService, sessionService, userService,
                lifecycleProvider, permissionProvider);
        givenControlProcessLinkingTo(PEER);
    }

    @Test
    void closingControl_revokesGrantsThenDeletesTheAccount() {
        hook.onSessionClosed(session(CONTROL));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(permissionBootstrap, userService);
        order.verify(permissionBootstrap).revokeAll(TENANT, ACCOUNT);
        order.verify(userService).delete(TENANT, ACCOUNT);
        verify(lifecycleService).closeWithCascade(PEER);
    }

    @Test
    void archivingDoesNotTouchTheAccount() {
        hook.onSessionArchived(session(CONTROL));

        // The whole point of the move to session level: archive and close
        // are different methods here and cannot be confused, whereas at
        // process level both look like CLOSED — and the close reason that
        // would tell them apart is written after the event.
        verify(userService, never()).delete(any(), any());
        verify(permissionBootstrap, never()).revokeAll(any(), any());
    }

    @Test
    void archivingControl_archivesTheWorkerSession() {
        hook.onSessionArchived(session(CONTROL));

        verify(lifecycleService).archiveWithCascade(PEER);
        // Archived means put away, not thrown away — nothing is deleted.
        verify(lifecycleService, never()).deleteSession(any());
    }

    @Test
    void deletingControl_deletesTheWorkerSession() {
        hook.onSessionDeleted(session(CONTROL));

        verify(lifecycleService).deleteSession(PEER);
    }

    @Test
    void reactivating_clearsTheDeadWorkerSession() {
        hook.onSessionUnarchived(session(CONTROL));

        // Its processes are CLOSED and a closed process never returns;
        // the bootstrapper rebuilds around the same account.
        verify(lifecycleService).deleteSession(PEER);
    }

    @Test
    void aSessionWithoutAWorker_isLeftAlone() {
        when(thinkProcessService.findBySession(TENANT, CONTROL)).thenReturn(List.of(plainProcess()));

        hook.onSessionArchived(session(CONTROL));
        hook.onSessionDeleted(session(CONTROL));
        hook.onSessionUnarchived(session(CONTROL));

        verify(lifecycleService, never()).archiveWithCascade(any());
        verify(lifecycleService, never()).deleteSession(any());
    }

    @Test
    void theWorkerSessionItselfDoesNothing() {
        // The worker carries peerSessionId too — pointing back at
        // control — and its own trillianUserName. Keying on the wiring
        // made delete bounce between the two until the stack ran out,
        // and made the worker delete the shared account on the way.
        // The control engine is the only reliable discriminator.
        when(thinkProcessService.findBySession(TENANT, PEER))
                .thenReturn(List.of(workerProcess()));

        hook.onSessionClosed(session(PEER));
        hook.onSessionArchived(session(PEER));
        hook.onSessionUnarchived(session(PEER));
        hook.onSessionDeleted(session(PEER));

        verify(lifecycleService, never()).archiveWithCascade(any());
        verify(lifecycleService, never()).deleteSession(any());
        verify(lifecycleService, never()).closeWithCascade(any());
        verify(userService, never()).delete(any(), any());
    }

    @Test
    void olderCyclesAreSkipped_theCurrentWorkerWins() {
        // Every archive/reactivate leaves another closed chat-process
        // behind with the peerSessionId it had then. Taking the first
        // match picked an arbitrary generation: live, the hook went
        // looking for a worker deleted two cycles earlier, found it gone,
        // and silently did nothing — no attributes carried, the real
        // worker session orphaned.
        when(thinkProcessService.findBySession(TENANT, CONTROL)).thenReturn(List.of(
                agedControlProcess("stale-worker", java.time.Instant.parse("2026-08-11T10:00:00Z")),
                agedControlProcess(PEER, java.time.Instant.parse("2026-08-13T12:00:00Z"))));
        when(sessionService.findBySessionId("stale-worker"))
                .thenReturn(java.util.Optional.empty());

        hook.onSessionDeleted(session(CONTROL));

        verify(lifecycleService).deleteSession(PEER);
        verify(lifecycleService, never()).deleteSession("stale-worker");
    }

    @Test
    void aDeadNewestLink_fallsBackToAnOlderLiveOne() {
        when(thinkProcessService.findBySession(TENANT, CONTROL)).thenReturn(List.of(
                agedControlProcess(PEER, java.time.Instant.parse("2026-08-11T10:00:00Z")),
                agedControlProcess("dead-worker", java.time.Instant.parse("2026-08-13T12:00:00Z"))));
        when(sessionService.findBySessionId("dead-worker"))
                .thenReturn(java.util.Optional.empty());

        hook.onSessionDeleted(session(CONTROL));

        verify(lifecycleService).deleteSession(PEER);
    }

    @Test
    void theLinkIsFoundOnAnyProcess_notJustTheCurrentChat() {
        // After a reactivate the link lives on the renamed, closed
        // predecessor rather than on the live chat process.
        when(thinkProcessService.findBySession(TENANT, CONTROL))
                .thenReturn(List.of(plainProcess(), controlProcess(PEER)));

        hook.onSessionDeleted(session(CONTROL));

        verify(lifecycleService).deleteSession(PEER);
    }

    @Test
    void aFailingCascade_doesNotEscape() {
        // The caller logs and carries on: a hook must not block the
        // transition the user asked for.
        org.mockito.Mockito.doThrow(new IllegalStateException("mongo down"))
                .when(lifecycleService).deleteSession(PEER);

        assertThatCode(() -> hook.onSessionDeleted(session(CONTROL)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    @Test
    void aWorkerSessionThatIsAlreadyGone_producesNoClaimOfWork() {
        // A control session keeps the wiring on its closed processes long
        // after a reactivate removed the worker. Acting on that logged
        // "archived worker session …" for a session that did not exist.
        when(sessionService.findBySessionId(PEER)).thenReturn(java.util.Optional.empty());

        hook.onSessionArchived(session(CONTROL));
        hook.onSessionDeleted(session(CONTROL));

        verify(lifecycleService, never()).archiveWithCascade(any());
        verify(lifecycleService, never()).deleteSession(any());
    }

    private void givenControlProcessLinkingTo(String peerSessionId) {
        when(thinkProcessService.findBySession(TENANT, CONTROL))
                .thenReturn(List.of(controlProcess(peerSessionId)));
    }

    private static SessionDocument session(String sessionId) {
        SessionDocument s = new SessionDocument();
        s.setSessionId(sessionId);
        s.setTenantId(TENANT);
        return s;
    }

    private static ThinkProcessDocument controlProcess(String peerSessionId) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("control-proc");
        p.setTenantId(TENANT);
        p.setSessionId(CONTROL);
        p.setThinkEngine(TrillianSessionBootstrapper.CONTROL_ENGINE_NAME);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID, peerSessionId);
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, "_trillian-04801");
        p.setEngineParams(params);
        return p;
    }

    /** The worker loop: same wiring keys, pointing the other way. */
    private static ThinkProcessDocument workerProcess() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("worker-proc");
        p.setTenantId(TENANT);
        p.setSessionId(PEER);
        p.setThinkEngine("trillian-user");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID, CONTROL);
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, ACCOUNT);
        p.setEngineParams(params);
        return p;
    }

    private static ThinkProcessDocument agedControlProcess(
            String peerSessionId, java.time.Instant createdAt) {
        ThinkProcessDocument p = controlProcess(peerSessionId);
        p.setId("control-proc-" + peerSessionId);
        p.setCreatedAt(createdAt);
        return p;
    }

    private static ThinkProcessDocument plainProcess() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("other-proc");
        p.setTenantId(TENANT);
        p.setSessionId(CONTROL);
        p.setEngineParams(new LinkedHashMap<>());
        return p;
    }
}
