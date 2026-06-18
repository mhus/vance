package de.mhus.vance.brain.ws.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.DocumentPresenceNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

class DocumentSubscriberRegistryTest {

    private WebSocketSender sender;
    private DocumentSubscriberRegistry registry;

    @BeforeEach
    void setUp() {
        sender = mock(WebSocketSender.class);
        registry = new DocumentSubscriberRegistry(sender);
    }

    @Test
    void subscribe_addsEntry_andBroadcastsPresenceToSelfFiltered() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");

        registry.subscribe(ws, ctx, "notes.md");

        assertThat(registry.viewersOf("notes.md")).hasSize(1);

        // Broadcast goes out to the only subscriber — but filtered: self-editorId
        // removed → empty list to the only subscriber.
        DocumentPresenceNotification push = captureLastPresencePush(ws);
        assertThat(push.getPath()).isEqualTo("notes.md");
        assertThat(push.getViewers()).isEmpty();
    }

    @Test
    void twoTabsSameUser_eachSeesTheOther_butNeverSelf() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        ConnectionContext ctxA = contextFor("ed-A", "alice", "Alice");
        ConnectionContext ctxB = contextFor("ed-B", "alice", "Alice");

        registry.subscribe(wsA, ctxA, "notes.md");
        registry.subscribe(wsB, ctxB, "notes.md");

        // After both subscriptions, both got a broadcast. The last one Tab A
        // received was the one triggered by Tab B's subscribe.
        DocumentPresenceNotification pushToA = captureLastPresencePush(wsA);
        DocumentPresenceNotification pushToB = captureLastPresencePush(wsB);

        // Tab A's list contains only ed-B (its own ed-A filtered out).
        assertThat(pushToA.getViewers()).hasSize(1);
        assertThat(pushToA.getViewers().get(0).getEditorId()).isEqualTo("ed-B");
        assertThat(pushToA.getViewers().get(0).getUserId()).isEqualTo("alice");

        // Tab B's list contains only ed-A.
        assertThat(pushToB.getViewers()).hasSize(1);
        assertThat(pushToB.getViewers().get(0).getEditorId()).isEqualTo("ed-A");
    }

    @Test
    void multipleUsers_eachSeesOnlyOthers() throws IOException {
        WebSocketSession wsAlice = wsSession("ws-1");
        WebSocketSession wsBob = wsSession("ws-2");
        WebSocketSession wsCarol = wsSession("ws-3");
        registry.subscribe(wsAlice, contextFor("ed-1", "alice", "Alice"), "notes.md");
        registry.subscribe(wsBob, contextFor("ed-2", "bob", "Bob"), "notes.md");
        registry.subscribe(wsCarol, contextFor("ed-3", "carol", "Carol"), "notes.md");

        // Latest broadcast received by Alice excludes her own editorId
        DocumentPresenceNotification toAlice = captureLastPresencePush(wsAlice);
        assertThat(toAlice.getViewers()).extracting("editorId")
                .containsExactlyInAnyOrder("ed-2", "ed-3");
        assertThat(toAlice.getViewers()).extracting("userId")
                .containsExactlyInAnyOrder("bob", "carol");
    }

    @Test
    void unsubscribe_dropsEntry_andBroadcastsToRemaining() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "notes.md");
        registry.subscribe(wsB, contextFor("ed-B", "bob", "Bob"), "notes.md");

        registry.unsubscribe(wsA, "notes.md");

        assertThat(registry.viewersOf("notes.md")).hasSize(1);
        // After unsubscribe: Bob got a presence push with empty viewers
        // (Alice — the unsubscriber — is gone). Bob is the only one left
        // so his own ed-B is filtered out → empty list.
        DocumentPresenceNotification toBob = captureLastPresencePush(wsB);
        assertThat(toBob.getViewers()).isEmpty();
    }

    @Test
    void unsubscribeAll_dropsAllPaths_forOneSession() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "notes.md");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "tasks.md");
        registry.subscribe(wsB, contextFor("ed-B", "bob", "Bob"), "notes.md");

        registry.unsubscribeAll(wsA);

        assertThat(registry.viewersOf("notes.md")).hasSize(1);
        assertThat(registry.viewersOf("notes.md").get(0).getEditorId()).isEqualTo("ed-B");
        assertThat(registry.viewersOf("tasks.md")).isEmpty();
    }

    @Test
    void unsubscribe_unknownPath_isNoOp() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        registry.unsubscribe(wsA, "never-subscribed.md");
        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    @Test
    void pathsOf_returnsCurrentSubscriptions() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "a.md");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "b.md");

        assertThat(registry.pathsOf(ws)).containsExactlyInAnyOrder("a.md", "b.md");

        registry.unsubscribe(ws, "a.md");
        assertThat(registry.pathsOf(ws)).containsExactly("b.md");
    }

    @Test
    void resubscribe_sameWs_samePath_isIdempotent_butStillBroadcasts() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");

        registry.subscribe(ws, ctx, "notes.md");
        registry.subscribe(ws, ctx, "notes.md");

        assertThat(registry.viewersOf("notes.md")).hasSize(1);
        // Two broadcasts even though state didn't change — gives the
        // client a fresh roster, helpful right after reconnect-resubscribe.
        verify(sender, times(2)).sendOnChannel(eq(ws), eq("documents"), any());
    }

    // ── helpers ────────────────────────────────────────────────

    private static WebSocketSession wsSession(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        return ws;
    }

    private static ConnectionContext contextFor(String editorId, String userId, String displayName) {
        return new ConnectionContext(
                "acme", userId, displayName, "web", "1.0", null,
                editorId, "10.0.0.1");
    }

    private DocumentPresenceNotification captureLastPresencePush(WebSocketSession ws) throws IOException {
        ArgumentCaptor<WebSocketEnvelope> cap = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendOnChannel(eq(ws), eq("documents"), cap.capture());
        WebSocketEnvelope last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getType()).isEqualTo(MessageType.DOCUMENT_PRESENCE);
        return (DocumentPresenceNotification) last.getData();
    }
}
