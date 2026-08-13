package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.session.SessionLifecycleService;
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

    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    SessionLifecycleService lifecycleService;
    @Mock
    ObjectProvider<SessionLifecycleService> lifecycleProvider;

    TrillianSessionLifecycleHook hook;

    @BeforeEach
    void setUp() {
        when(lifecycleProvider.getObject()).thenReturn(lifecycleService);
        hook = new TrillianSessionLifecycleHook(thinkProcessService, lifecycleProvider);
        givenControlProcessLinkingTo(PEER);
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

        // This is also what stops the cascade recursing: the worker
        // session carries no peerSessionId, so deleting it through the
        // same service does not re-enter here.
        verify(lifecycleService, never()).archiveWithCascade(any());
        verify(lifecycleService, never()).deleteSession(any());
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_PEER_SESSION_ID, peerSessionId);
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, "_trillian-04801");
        p.setEngineParams(params);
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
