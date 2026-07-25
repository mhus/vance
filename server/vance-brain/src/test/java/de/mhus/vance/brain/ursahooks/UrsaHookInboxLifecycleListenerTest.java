package de.mhus.vance.brain.ursahooks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.shared.inbox.InboxItemCreatedEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cycle guard for the inbox-side self-triggering hook chain — the mirror of
 * {@link UrsaHookProcessLifecycleListenerTest}, which was the only cycle-guard
 * covered. A hook on {@code inbox.item.created} whose action posts an inbox
 * item would otherwise loop forever (the guard is a single-hop tag check, not a
 * depth bound): an inbox item whose origin process is HOOK-spawned must NOT
 * re-fire {@code inbox.item.created}. Non-hook origins fan out normally.
 */
class UrsaHookInboxLifecycleListenerTest {

    private ApplicationEventPublisher publisher;
    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private UrsaHookInboxLifecycleListener listener;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        listener = new UrsaHookInboxLifecycleListener(publisher, sessionService, thinkProcessService);
    }

    @Test
    void hookSpawnedOrigin_doesNotRefireInboxCreated_evenWithResolvableSession() {
        InboxItemDocument item = mock(InboxItemDocument.class);
        when(item.getOriginProcessId()).thenReturn("p-hook");
        // A resolvable session is present — proves the guard short-circuits
        // BEFORE project resolution / fan-out.
        when(item.getOriginSessionId()).thenReturn("s-1");

        ThinkProcessDocument hookProc = mock(ThinkProcessDocument.class);
        when(hookProc.getTriggerSource()).thenReturn(TriggerKind.HOOK.name());
        when(thinkProcessService.findById("p-hook")).thenReturn(Optional.of(hookProc));

        listener.onCreated(new InboxItemCreatedEvent(item));

        verify(publisher, never()).publishEvent(any());
        verify(sessionService, never()).findBySessionId(any());
    }

    @Test
    void normalOrigin_firesInboxCreated() {
        InboxItemDocument item = mock(InboxItemDocument.class);
        when(item.getOriginProcessId()).thenReturn(null); // not hook-spawned
        when(item.getOriginSessionId()).thenReturn("s-1");
        when(item.getTenantId()).thenReturn("acme");

        SessionDocument session = mock(SessionDocument.class);
        when(session.getProjectId()).thenReturn("instant-hole");
        when(sessionService.findBySessionId("s-1")).thenReturn(Optional.of(session));

        listener.onCreated(new InboxItemCreatedEvent(item));

        verify(publisher).publishEvent(any(UrsaHookFireableEvent.class));
    }

    @Test
    void unresolvableScope_dropsSilently() {
        // No origin process (guard skipped) and no session → no project → drop,
        // never fan out (a tool-driven item without scope can't reach project hooks).
        InboxItemDocument item = mock(InboxItemDocument.class);
        when(item.getOriginProcessId()).thenReturn(null);
        when(item.getOriginSessionId()).thenReturn(null);

        listener.onCreated(new InboxItemCreatedEvent(item));

        verify(publisher, never()).publishEvent(any());
    }
}
