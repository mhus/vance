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
import de.mhus.vance.api.ws.DocumentViewer;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.io.IOException;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class DocumentSubscriberRegistryTest {

    private WebSocketSender sender;
    private VanceRedisMessagingService messaging;
    private ClusterService clusterService;
    private DocumentSubscriberRegistry registry;

    @BeforeEach
    void setUp() {
        sender = mock(WebSocketSender.class);
        messaging = mock(VanceRedisMessagingService.class);
        clusterService = mock(ClusterService.class);
        when(clusterService.selfNodeName()).thenReturn("pod-self");
        registry = new DocumentSubscriberRegistry(
                sender, messaging, clusterService, new ObjectMapper());
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

    // ── cross-pod sync ────────────────────────────────────────

    @Test
    void subscribe_publishesAddDeltaOnRedis() throws Exception {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(messaging).publish(eq("acme"), eq("documents.presence"), json.capture());
        PresenceDelta delta = new ObjectMapper().readValue(json.getValue(), PresenceDelta.class);
        assertThat(delta.getAction()).isEqualTo(PresenceDelta.Action.ADD);
        assertThat(delta.getPath()).isEqualTo("notes.md");
        assertThat(delta.getViewer().getEditorId()).isEqualTo("ed-1");
        assertThat(delta.getPodId()).isEqualTo("pod-self");
        assertThat(delta.getTenantId()).isEqualTo("acme");
    }

    @Test
    void unsubscribe_publishesRemoveDeltaOnRedis() throws Exception {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");
        org.mockito.Mockito.reset(messaging);

        registry.unsubscribe(ws, "notes.md");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(messaging).publish(eq("acme"), eq("documents.presence"), json.capture());
        PresenceDelta delta = new ObjectMapper().readValue(json.getValue(), PresenceDelta.class);
        assertThat(delta.getAction()).isEqualTo(PresenceDelta.Action.REMOVE);
        assertThat(delta.getViewer().getEditorId()).isEqualTo("ed-1");
    }

    @Test
    void remoteAdd_isMergedIntoViewersAndBroadcast() throws Exception {
        WebSocketSession localWs = wsSession("ws-local");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "shared.md");
        org.mockito.Mockito.reset(sender);

        BiConsumer<String, String> remoteHandler = captureRemoteHandler();
        PresenceDelta remoteAdd = PresenceDelta.builder()
                .podId("pod-other")
                .tenantId("acme")
                .action(PresenceDelta.Action.ADD)
                .path("shared.md")
                .viewer(DocumentViewer.builder()
                        .editorId("ed-remote")
                        .userId("bob")
                        .displayName("Bob")
                        .build())
                .build();
        remoteHandler.accept("vance:acme:documents.presence",
                new ObjectMapper().writeValueAsString(remoteAdd));

        DocumentPresenceNotification push = captureLastPresencePush(localWs);
        assertThat(push.getViewers()).extracting("editorId").containsExactly("ed-remote");
        assertThat(registry.viewersOf("shared.md"))
                .extracting("editorId")
                .containsExactlyInAnyOrder("ed-local", "ed-remote");
    }

    @Test
    void remoteRemove_dropsRemoteViewerAndBroadcasts() throws Exception {
        WebSocketSession localWs = wsSession("ws-local");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "shared.md");
        BiConsumer<String, String> remoteHandler = captureRemoteHandler();
        ObjectMapper json = new ObjectMapper();
        DocumentViewer remoteViewer = DocumentViewer.builder()
                .editorId("ed-remote").userId("bob").displayName("Bob").build();
        remoteHandler.accept("vance:acme:documents.presence", json.writeValueAsString(
                PresenceDelta.builder().podId("pod-other").tenantId("acme")
                        .action(PresenceDelta.Action.ADD).path("shared.md").viewer(remoteViewer).build()));
        org.mockito.Mockito.reset(sender);

        remoteHandler.accept("vance:acme:documents.presence", json.writeValueAsString(
                PresenceDelta.builder().podId("pod-other").tenantId("acme")
                        .action(PresenceDelta.Action.REMOVE).path("shared.md").viewer(remoteViewer).build()));

        assertThat(registry.viewersOf("shared.md"))
                .extracting("editorId").containsExactly("ed-local");
    }

    @Test
    void remoteClearPod_dropsAllEntriesForThatPod() throws Exception {
        WebSocketSession localWs = wsSession("ws-local");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "a.md");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "b.md");
        BiConsumer<String, String> remoteHandler = captureRemoteHandler();
        ObjectMapper json = new ObjectMapper();
        DocumentViewer v1 = DocumentViewer.builder()
                .editorId("ed-r1").userId("bob").displayName("Bob").build();
        DocumentViewer v2 = DocumentViewer.builder()
                .editorId("ed-r2").userId("bob").displayName("Bob").build();
        remoteHandler.accept("vance:acme:documents.presence", json.writeValueAsString(
                PresenceDelta.builder().podId("pod-other").tenantId("acme")
                        .action(PresenceDelta.Action.ADD).path("a.md").viewer(v1).build()));
        remoteHandler.accept("vance:acme:documents.presence", json.writeValueAsString(
                PresenceDelta.builder().podId("pod-other").tenantId("acme")
                        .action(PresenceDelta.Action.ADD).path("b.md").viewer(v2).build()));

        remoteHandler.accept("vance:acme:documents.presence", json.writeValueAsString(
                PresenceDelta.builder().podId("pod-other").tenantId("acme")
                        .action(PresenceDelta.Action.CLEAR_POD).build()));

        assertThat(registry.viewersOf("a.md")).extracting("editorId").containsExactly("ed-local");
        assertThat(registry.viewersOf("b.md")).extracting("editorId").containsExactly("ed-local");
    }

    @Test
    void ownPodDelta_loopback_isIgnored() throws Exception {
        BiConsumer<String, String> remoteHandler = captureRemoteHandler();
        DocumentViewer phantom = DocumentViewer.builder()
                .editorId("ed-phantom").userId("alice").displayName("Alice").build();
        remoteHandler.accept("vance:acme:documents.presence", new ObjectMapper().writeValueAsString(
                PresenceDelta.builder().podId("pod-self") // ← own pod
                        .tenantId("acme").action(PresenceDelta.Action.ADD)
                        .path("notes.md").viewer(phantom).build()));

        // Phantom must NOT show up — own publishes shouldn't double-count.
        assertThat(registry.viewersOf("notes.md")).isEmpty();
    }

    @Test
    void pruneDeadPods_dropsEntriesForOfflinePods() throws Exception {
        WebSocketSession localWs = wsSession("ws-local");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "shared.md");
        BiConsumer<String, String> remoteHandler = captureRemoteHandler();
        DocumentViewer remoteViewer = DocumentViewer.builder()
                .editorId("ed-remote").userId("bob").displayName("Bob").build();
        remoteHandler.accept("vance:acme:documents.presence", new ObjectMapper().writeValueAsString(
                PresenceDelta.builder().podId("pod-dead").tenantId("acme")
                        .action(PresenceDelta.Action.ADD).path("shared.md")
                        .viewer(remoteViewer).build()));
        assertThat(registry.viewersOf("shared.md")).hasSize(2);

        when(clusterService.liveClusterNodeNames()).thenReturn(java.util.Set.of("pod-self"));
        registry.pruneDeadPods();

        assertThat(registry.viewersOf("shared.md"))
                .extracting("editorId").containsExactly("ed-local");
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

    /**
     * Pull the cross-pod-delta handler out of the messaging mock — that's
     * how the registry hooked into Redis-pubsub in its {@code @PostConstruct}.
     */
    @SuppressWarnings("unchecked")
    private BiConsumer<String, String> captureRemoteHandler() {
        // Invoke @PostConstruct explicitly — Mockito-only setup doesn't
        // trigger Spring lifecycle hooks.
        registry.start();
        ArgumentCaptor<BiConsumer<String, String>> cap =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(messaging, org.mockito.Mockito.atLeastOnce())
                .subscribeAcrossTenants(eq("documents.presence"), cap.capture());
        return cap.getValue();
    }
}
