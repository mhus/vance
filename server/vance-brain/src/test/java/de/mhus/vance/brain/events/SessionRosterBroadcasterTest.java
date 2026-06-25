package de.mhus.vance.brain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.session.SessionRosterData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

/**
 * Verifies the multi-user roster broadcast pipeline — see
 * {@code planning/multi-user-sessions.md} §7. The
 * {@link SessionConnectionRegistry} fires
 * {@link SessionRosterChangedEvent}; the broadcaster reads the live
 * registry snapshot and pushes a {@code session-roster} frame to
 * every connection.
 *
 * <p>Tests bypass the {@code @Async} annotation by invoking
 * {@code onRosterChanged} directly — covers the wire-format
 * + fan-out logic in isolation. The async-execution gate is a
 * Spring concern and is already covered by Spring's contract.
 */
class SessionRosterBroadcasterTest {

    private SessionConnectionRegistry registry;
    private WebSocketSender sender;
    private SessionRosterBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        registry = new SessionConnectionRegistry();
        sender = mock(WebSocketSender.class);
        broadcaster = new SessionRosterBroadcaster(registry, sender);
    }

    @Test
    void broadcast_singleConnection_pushesRosterWithOneParticipant() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", "Alice Smith", ws, false);

        broadcaster.onRosterChanged(new SessionRosterChangedEvent("s1"));

        ArgumentCaptor<SessionRosterData> payload =
                ArgumentCaptor.forClass(SessionRosterData.class);
        verify(sender).sendNotification(eq(ws),
                eq(MessageType.SESSION_ROSTER), payload.capture());
        assertThat(payload.getValue().getSessionId()).isEqualTo("s1");
        assertThat(payload.getValue().getParticipants()).hasSize(1);
        assertThat(payload.getValue().getParticipants().get(0).getDisplayName())
                .isEqualTo("Alice Smith");
        assertThat(payload.getValue().getParticipants().get(0).getUserId())
                .isEqualTo("alice");
        assertThat(payload.getValue().getParticipants().get(0).getEditorId())
                .isEqualTo("ed-1");
    }

    @Test
    void broadcast_twoConnections_pushesToBothWithBothParticipants() throws Exception {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", "Alice", aliceWs, true);
        registry.register("s1", "bob", "ed-2", "Bob", bobWs, true);

        broadcaster.onRosterChanged(new SessionRosterChangedEvent("s1"));

        verify(sender, times(2)).sendNotification(any(),
                eq(MessageType.SESSION_ROSTER), any());
        ArgumentCaptor<SessionRosterData> payload =
                ArgumentCaptor.forClass(SessionRosterData.class);
        verify(sender).sendNotification(eq(aliceWs),
                eq(MessageType.SESSION_ROSTER), payload.capture());
        assertThat(payload.getValue().getParticipants()).hasSize(2);
        assertThat(payload.getValue().getParticipants())
                .extracting(p -> p.getUserId())
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void broadcast_noConnections_isNoop() throws Exception {
        broadcaster.onRosterChanged(new SessionRosterChangedEvent("s1"));

        verify(sender, never()).sendNotification(any(), any(), any());
    }

    @Test
    void registry_register_publishesEvent() {
        // Wire a manual publisher to confirm the registry actually
        // emits the event the broadcaster relies on.
        org.springframework.context.ApplicationEventPublisher pub =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        registry.setEventPublisher(pub);
        WebSocketSession ws = mock(WebSocketSession.class);

        registry.register("s1", "alice", "ed-1", "Alice", ws, false);

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(pub).publishEvent(evt.capture());
        assertThat(evt.getValue()).isInstanceOf(SessionRosterChangedEvent.class);
        assertThat(((SessionRosterChangedEvent) evt.getValue()).sessionId())
                .isEqualTo("s1");
    }

    @Test
    void registry_unregister_publishesEvent() {
        org.springframework.context.ApplicationEventPublisher pub =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        registry.setEventPublisher(pub);
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", null, ws, false);

        registry.unregister("s1", "ed-1");

        // 2 events: 1 from register + 1 from unregister
        verify(pub, times(2)).publishEvent(any(SessionRosterChangedEvent.class));
    }
}
