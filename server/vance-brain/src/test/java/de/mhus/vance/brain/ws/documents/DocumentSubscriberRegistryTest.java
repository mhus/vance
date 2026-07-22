package de.mhus.vance.brain.ws.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.DocumentPresenceNotification;
import de.mhus.vance.api.ws.DocumentViewer;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class DocumentSubscriberRegistryTest {

    private WebSocketSender sender;
    private VanceRedisMessagingService redis;
    private DocumentSubscriberRegistry registry;

    /**
     * Simulated Redis HASH backing store: subKey → (field → value).
     * Wired into the {@link VanceRedisMessagingService} mock below so
     * {@code hashPut}/{@code hashDelete}/{@code hashGetAll} behave like
     * a real (single-tenant) Redis would.
     */
    private final Map<String, Map<String, String>> hashState = new HashMap<>();

    @BeforeEach
    void setUp() {
        sender = mock(WebSocketSender.class);
        redis = mock(VanceRedisMessagingService.class);
        when(redis.isEnabled()).thenReturn(true);

        // hashPut(tenant, subKey, field, value, ttl) → write into hashState
        doAnswer(inv -> {
            String subKey = inv.getArgument(1);
            String field = inv.getArgument(2);
            String value = inv.getArgument(3);
            hashState.computeIfAbsent(subKey, k -> new HashMap<>()).put(field, value);
            return null;
        }).when(redis).hashPut(anyString(), anyString(), anyString(), anyString(), any(Duration.class));

        // hashDelete(tenant, subKey, field) → remove
        doAnswer(inv -> {
            String subKey = inv.getArgument(1);
            String field = inv.getArgument(2);
            Map<String, String> fields = hashState.get(subKey);
            if (fields != null) {
                fields.remove(field);
                if (fields.isEmpty()) hashState.remove(subKey);
            }
            return null;
        }).when(redis).hashDelete(anyString(), anyString(), anyString());

        // hashGetAll(tenant, subKey) → snapshot
        when(redis.hashGetAll(anyString(), anyString())).thenAnswer(inv -> {
            String subKey = inv.getArgument(1);
            Map<String, String> fields = hashState.get(subKey);
            return fields == null ? Map.of() : Map.copyOf(fields);
        });

        registry = new DocumentSubscriberRegistry(sender, redis, new ObjectMapper());
        // @PostConstruct is not run by Mockito; invoke explicitly so the
        // remote-handler is registered.
        registry.start();
    }

    @Test
    void subscribe_storesViewerInRedis_andBroadcastsSelfFiltered() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");

        registry.subscribe(ws, ctx, "notes.md");

        // Viewer field landed in the hash.
        assertThat(hashState.get(subKeyOf("notes.md"))).containsKey("ed-1");
        // Broadcast: only subscriber → self-filtered to empty list.
        DocumentPresenceNotification push = captureLastPresencePush(ws);
        assertThat(push.getPath()).isEqualTo("notes.md");
        assertThat(push.getViewers()).isEmpty();
    }

    @Test
    void subscribe_publishesPathChangedSignal() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq("acme"), eq("documents.presence"), payload.capture());
        // Payload format: "{podId}|{base64(path)}". Self-podId at the front,
        // base64-encoded path as the tail.
        String[] parts = payload.getValue().split("\\|", 2);
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isNotBlank();
        assertThat(decodeUrl(parts[1])).isEqualTo("notes.md");
    }

    @Test
    void twoTabsSameUser_eachSeesTheOther_butNeverSelf() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "notes.md");
        registry.subscribe(wsB, contextFor("ed-B", "alice", "Alice"), "notes.md");

        DocumentPresenceNotification pushToA = captureLastPresencePush(wsA);
        DocumentPresenceNotification pushToB = captureLastPresencePush(wsB);
        assertThat(pushToA.getViewers()).extracting("editorId").containsExactly("ed-B");
        assertThat(pushToB.getViewers()).extracting("editorId").containsExactly("ed-A");
    }

    @Test
    void multipleUsers_eachSeesOnlyOthers() throws IOException {
        WebSocketSession wsAlice = wsSession("ws-1");
        registry.subscribe(wsAlice, contextFor("ed-1", "alice", "Alice"), "notes.md");
        registry.subscribe(wsSession("ws-2"), contextFor("ed-2", "bob", "Bob"), "notes.md");
        registry.subscribe(wsSession("ws-3"), contextFor("ed-3", "carol", "Carol"), "notes.md");

        DocumentPresenceNotification toAlice = captureLastPresencePush(wsAlice);
        assertThat(toAlice.getViewers()).extracting("editorId")
                .containsExactlyInAnyOrder("ed-2", "ed-3");
        assertThat(toAlice.getViewers()).extracting("userId")
                .containsExactlyInAnyOrder("bob", "carol");
    }

    @Test
    void unsubscribe_dropsViewerFromRedis_andBroadcasts() throws IOException {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "notes.md");
        registry.subscribe(wsB, contextFor("ed-B", "bob", "Bob"), "notes.md");

        registry.unsubscribe(wsA, "notes.md");

        assertThat(hashState.get(subKeyOf("notes.md"))).containsOnlyKeys("ed-B");
        // Bob's last push: only viewer left is himself → empty (self-filtered).
        DocumentPresenceNotification toBob = captureLastPresencePush(wsB);
        assertThat(toBob.getViewers()).isEmpty();
    }

    @Test
    void unsubscribeAll_clearsAllPathsForOneSession() {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "notes.md");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "tasks.md");
        registry.subscribe(wsB, contextFor("ed-B", "bob", "Bob"), "notes.md");

        registry.unsubscribeAll(wsA);

        assertThat(hashState.get(subKeyOf("notes.md"))).containsOnlyKeys("ed-B");
        assertThat(hashState.get(subKeyOf("tasks.md"))).isNull();
    }

    @Test
    void unsubscribe_unknownPath_isNoOp() throws IOException {
        WebSocketSession ws = wsSession("ws-A");
        registry.unsubscribe(ws, "never-subscribed.md");
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
    void resubscribe_sameWs_samePath_stillBroadcasts() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");
        registry.subscribe(ws, ctx, "notes.md");
        registry.subscribe(ws, ctx, "notes.md");

        // Two pushes — gives the client a fresh roster on reconnect-resubscribe.
        verify(sender, times(2)).sendOnChannel(eq(ws), eq("documents"), any());
    }

    // ── cross-pod sync ────────────────────────────────────────

    @Test
    void remoteChanged_otherPod_rebroadcastsToLocalSubscribers() throws Exception {
        // Simulate: another pod already wrote a viewer into the Redis hash.
        WebSocketSession localWs = wsSession("ws-local");
        registry.subscribe(localWs, contextFor("ed-local", "alice", "Alice"), "shared.md");
        // Manually inject a remote viewer into the simulated hash.
        Map<String, String> field = hashState.computeIfAbsent(subKeyOf("shared.md"), k -> new HashMap<>());
        field.put("ed-remote", new ObjectMapper().writeValueAsString(Map.of(
                "editorId", "ed-remote",
                "userId", "bob",
                "displayName", "Bob",
                "podId", "pod-other")));
        org.mockito.Mockito.reset(sender);

        // Fire the cross-pod "changed:path" signal from the foreign pod.
        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.presence",
                "pod-other|" + encodeUrl("shared.md"));

        DocumentPresenceNotification push = captureLastPresencePush(localWs);
        assertThat(push.getViewers()).extracting("editorId").containsExactly("ed-remote");
    }

    @Test
    void remoteChanged_selfEcho_isIgnored() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");
        org.mockito.Mockito.reset(sender);

        // Simulate Redis looping back our OWN publish.
        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.presence",
                registry.podIdForTests() + "|" + encodeUrl("notes.md"));

        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    @Test
    void remoteChanged_malformedPayload_isIgnored() throws IOException {
        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.presence", "garbage-no-pipe");
        // No interaction with sender expected — nothing was broadcast.
        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    // ── viewers reading ────────────────────────────────────────

    @Test
    void viewersOf_readsThroughToRedis() throws Exception {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");
        // Inject a foreign-pod viewer alongside.
        hashState.get(subKeyOf("notes.md")).put("ed-2",
                new ObjectMapper().writeValueAsString(Map.of(
                        "editorId", "ed-2",
                        "userId", "bob",
                        "displayName", "Bob",
                        "podId", "pod-other")));

        assertThat(registry.viewersOf("notes.md"))
                .extracting("editorId")
                .containsExactlyInAnyOrder("ed-1", "ed-2");
    }

    @Test
    void viewersOf_unsubscribedPath_isEmpty() {
        assertThat(registry.viewersOf("never-touched.md")).isEmpty();
    }

    // ── heartbeat ───────────────────────────────────────────────

    @Test
    void heartbeat_refreshesAllLocalEntries() {
        WebSocketSession wsA = wsSession("ws-A");
        WebSocketSession wsB = wsSession("ws-B");
        registry.subscribe(wsA, contextFor("ed-A", "alice", "Alice"), "a.md");
        registry.subscribe(wsB, contextFor("ed-B", "bob", "Bob"), "b.md");
        org.mockito.Mockito.reset(redis);
        when(redis.isEnabled()).thenReturn(true);

        registry.heartbeat();

        verify(redis).hashPut(eq("acme"), eq(subKeyOf("a.md")), eq("ed-A"), any(), any(Duration.class));
        verify(redis).hashPut(eq("acme"), eq(subKeyOf("b.md")), eq("ed-B"), any(), any(Duration.class));
    }

    @Test
    void heartbeat_skipsWorkWhenRedisDisabled() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");
        when(redis.isEnabled()).thenReturn(false);
        org.mockito.Mockito.reset(redis);
        when(redis.isEnabled()).thenReturn(false);

        registry.heartbeat();

        verify(redis, never()).hashPut(any(), any(), any(), any(), any());
    }

    // ── folder-prefix subscriptions ───────────────────────────

    @Test
    void subscribePrefix_isTrackedInPrefixesOf() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribePrefix(ws, contextFor("ed-1", "alice", "Alice"), "calendars/q3/");
        assertThat(registry.prefixesOf(ws)).containsExactly("calendars/q3/");
    }

    @Test
    void subscribePrefix_doesNotWriteToRedisRoster() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribePrefix(ws, contextFor("ed-1", "alice", "Alice"), "calendars/q3/");
        // Prefix subs are silent watchers — no Redis presence-hash entry,
        // no presence-push (no Redis publish either).
        verify(redis, never()).hashPut(anyString(), anyString(), anyString(), anyString(), any(Duration.class));
        verify(redis, never()).publish(anyString(), anyString(), anyString());
    }

    @Test
    void forEachLocalSubscriber_isTenantScoped_doesNotLeakAcrossTenants() {
        // Same path string, two tenants → the fan-out must only reach the
        // event's tenant, else presence/content leaks across tenants
        // (code-review B2). documents.changed + documents.notes both route
        // their fan-out through this primitive.
        registry.subscribe(wsSession("ws-acme"),
                contextFor("ed-a", "alice", "Alice"), "notes.md");
        registry.subscribe(wsSession("ws-other"),
                new ConnectionContext("other", "bob", "Bob", "web", "1.0", null,
                        "ed-b", "10.0.0.2"),
                "notes.md");

        java.util.List<String> hitTenants = new java.util.ArrayList<>();
        registry.forEachLocalSubscriber("acme", "notes.md",
                (s, c) -> hitTenants.add(c.getTenantId()));

        assertThat(hitTenants).containsExactly("acme");
    }

    @Test
    void forEachLocalPrefixSubscriber_invokesActionForMatchingPath() {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");
        registry.subscribePrefix(ws, ctx, "calendars/q3/");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        registry.forEachLocalPrefixSubscriber("acme", "calendars/q3/lane-design/work.yaml",
                (s, c) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void forEachLocalPrefixSubscriber_skipsNonMatchingPath() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribePrefix(ws, contextFor("ed-1", "alice", "Alice"), "calendars/q3/");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        registry.forEachLocalPrefixSubscriber("acme", "calendars/q4/work.yaml",
                (s, c) -> calls.incrementAndGet());
        assertThat(calls.get()).isZero();
    }

    @Test
    void hasLocalSubscribers_trueWhenPrefixMatches() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribePrefix(ws, contextFor("ed-1", "alice", "Alice"), "calendars/q3/");
        assertThat(registry.hasLocalSubscribers("calendars/q3/lane/work.yaml")).isTrue();
        assertThat(registry.hasLocalSubscribers("other/file.md")).isFalse();
    }

    @Test
    void unsubscribePrefix_removesFromIndex() {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribePrefix(ws, contextFor("ed-1", "alice", "Alice"), "calendars/q3/");
        registry.unsubscribePrefix(ws, "calendars/q3/");
        assertThat(registry.prefixesOf(ws)).isEmpty();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        registry.forEachLocalPrefixSubscriber("acme", "calendars/q3/lane/work.yaml",
                (s, c) -> calls.incrementAndGet());
        assertThat(calls.get()).isZero();
    }

    @Test
    void unsubscribeAll_clearsBothPathAndPrefixSubs() {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");
        registry.subscribe(ws, ctx, "notes.md");
        registry.subscribePrefix(ws, ctx, "calendars/q3/");

        registry.unsubscribeAll(ws);

        assertThat(registry.pathsOf(ws)).isEmpty();
        assertThat(registry.prefixesOf(ws)).isEmpty();
    }

    @Test
    void subscriptionCountOf_sumsPathsAndPrefixes() {
        WebSocketSession ws = wsSession("ws-1");
        ConnectionContext ctx = contextFor("ed-1", "alice", "Alice");
        registry.subscribe(ws, ctx, "a.md");
        registry.subscribe(ws, ctx, "b.md");
        registry.subscribePrefix(ws, ctx, "calendars/q3/");
        assertThat(registry.subscriptionCountOf(ws)).isEqualTo(3);
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

    private static String subKeyOf(String path) {
        return DocumentSubscriberRegistry.hashSubKey(path);
    }

    private static String encodeUrl(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeUrl(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private DocumentPresenceNotification captureLastPresencePush(WebSocketSession ws) throws IOException {
        ArgumentCaptor<WebSocketEnvelope> cap = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendOnChannel(eq(ws), eq("documents"), cap.capture());
        WebSocketEnvelope last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getType()).isEqualTo(MessageType.DOCUMENT_PRESENCE);
        return (DocumentPresenceNotification) last.getData();
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<String, String> captureRemoteHandler() {
        ArgumentCaptor<BiConsumer<String, String>> cap =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .subscribeAcrossTenants(eq("documents.presence"), cap.capture());
        return cap.getValue();
    }
}
