package de.mhus.vance.brain.ws.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.DocumentChangedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.document.DocumentLiveChangedEvent;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class DocumentChangedBroadcasterTest {

    private WebSocketSender sender;
    private VanceRedisMessagingService redis;
    private DocumentSubscriberRegistry registry;
    private DocumentChangedBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        sender = mock(WebSocketSender.class);
        redis = mock(VanceRedisMessagingService.class);
        when(redis.isEnabled()).thenReturn(true);
        when(redis.hashGetAll(anyString(), anyString())).thenReturn(java.util.Map.of());
        doAnswer(inv -> null).when(redis).hashPut(anyString(), anyString(), anyString(), anyString(), any(Duration.class));

        registry = new DocumentSubscriberRegistry(sender, redis, new ObjectMapper());
        registry.start();

        broadcaster = new DocumentChangedBroadcaster(registry, redis, sender);
        broadcaster.start();
    }

    @Test
    void localEvent_publishesToRedis_andPushesToLocalSubscribers() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "documents/notes.md");
        org.mockito.Mockito.reset(sender);

        broadcaster.onLocalChanged(new DocumentLiveChangedEvent(
                "acme", "_user_alice", "documents/notes.md",
                DocumentLiveChangedEvent.Kind.UPSERTED,
                /* editorId */ null));

        // Wire payload: "{podId}|{base64(path)}|{kind}|{editorId?}" — 4 parts,
        // last one empty when no writer-identity was provided.
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq("acme"), eq("documents.changed"), payload.capture());
        String[] parts = payload.getValue().split("\\|", -1);
        assertThat(parts).hasSize(4);
        assertThat(decodeUrl(parts[1])).isEqualTo("documents/notes.md");
        assertThat(parts[2]).isEqualTo("upserted");
        assertThat(parts[3]).isEmpty();

        // Local push: DOCUMENT_CHANGED frame to subscriber (no editorId
        // on the event → writer-skip is a no-op, everyone receives).
        DocumentChangedNotification pushed = captureChanged(ws);
        assertThat(pushed.getPath()).isEqualTo("documents/notes.md");
        assertThat(pushed.getKind()).isEqualTo("upserted");
    }

    @Test
    void localEvent_withToolEditorId_doesNotSkipAnyRealSubscriber() throws IOException {
        // The "_tool" sentinel is the default for server-side writes
        // (LLM tools, scripts, Slartibartfast, schedulers). It must not
        // accidentally suppress any human editor: real clients have
        // random UUID editorIds, never the literal "_tool".
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "documents/notes.md");
        org.mockito.Mockito.reset(sender);

        broadcaster.onLocalChanged(new DocumentLiveChangedEvent(
                "acme", "_user_alice", "documents/notes.md",
                DocumentLiveChangedEvent.Kind.UPSERTED,
                "_tool"));

        DocumentChangedNotification pushed = captureChanged(ws);
        assertThat(pushed.getPath()).isEqualTo("documents/notes.md");
    }

    @Test
    void localEvent_withEditorId_includesItOnTheWire_andSkipsWriterOnFanOut() throws IOException {
        WebSocketSession wsWriter = wsSession("ws-writer");
        WebSocketSession wsOther = wsSession("ws-other");
        registry.subscribe(wsWriter, contextFor("ed-writer", "alice", "Alice"), "documents/notes.md");
        registry.subscribe(wsOther, contextFor("ed-other", "bob", "Bob"), "documents/notes.md");
        org.mockito.Mockito.reset(sender);

        broadcaster.onLocalChanged(new DocumentLiveChangedEvent(
                "acme", "_user_alice", "documents/notes.md",
                DocumentLiveChangedEvent.Kind.UPSERTED,
                "ed-writer"));

        // Writer's editorId appears as the 4th wire segment.
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq("acme"), eq("documents.changed"), payload.capture());
        String[] parts = payload.getValue().split("\\|", -1);
        assertThat(parts[3]).isEqualTo("ed-writer");

        // The writer's own WS must NOT receive the changed frame, the
        // other subscriber must.
        verify(sender, never()).sendOnChannel(eq(wsWriter), eq("documents"), any());
        verify(sender).sendOnChannel(eq(wsOther), eq("documents"), any());
    }

    @Test
    void localEvent_withNoLocalSubscribers_stillPublishesToRedis() throws IOException {
        broadcaster.onLocalChanged(new DocumentLiveChangedEvent(
                "acme", "_vance", "_vance/setting_forms/foo.yaml",
                DocumentLiveChangedEvent.Kind.UPSERTED,
                null));

        verify(redis).publish(eq("acme"), eq("documents.changed"), anyString());
        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    @Test
    void deletedEvent_carriesKindOnTheWire() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "documents/old.md");
        org.mockito.Mockito.reset(sender);

        broadcaster.onLocalChanged(new DocumentLiveChangedEvent(
                "acme", "_user_alice", "documents/old.md",
                DocumentLiveChangedEvent.Kind.DELETED,
                null));

        DocumentChangedNotification pushed = captureChanged(ws);
        assertThat(pushed.getKind()).isEqualTo("deleted");
    }

    // ── cross-pod sync ────────────────────────────────────────

    @Test
    void remoteChanged_otherPod_pushesToLocalSubscribers() throws IOException {
        WebSocketSession ws = wsSession("ws-local");
        registry.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared.md");
        org.mockito.Mockito.reset(sender);

        BiConsumer<String, String> handler = captureRemoteHandler();
        // 4-part wire format with empty editorId at the tail.
        handler.accept("vance:acme:documents.changed",
                "pod-other|" + encodeUrl("shared.md") + "|upserted|");

        DocumentChangedNotification pushed = captureChanged(ws);
        assertThat(pushed.getPath()).isEqualTo("shared.md");
        assertThat(pushed.getKind()).isEqualTo("upserted");
    }

    @Test
    void remoteChanged_withWriterEditorId_skipsThatSubscriber() throws IOException {
        WebSocketSession wsWriter = wsSession("ws-writer-tab");
        WebSocketSession wsOther = wsSession("ws-other-tab");
        // Imagine the writer's REST PUT went via Pod A; their WS lives on
        // Pod B (cross-pod sticky-session miss). Pod B receives the
        // Redis event with editorId="ed-writer" and must skip the
        // matching local WS while pushing to the other tab.
        registry.subscribe(wsWriter, contextFor("ed-writer", "alice", "Alice"), "shared.md");
        registry.subscribe(wsOther, contextFor("ed-other", "alice", "Alice"), "shared.md");
        org.mockito.Mockito.reset(sender);

        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.changed",
                "pod-other|" + encodeUrl("shared.md") + "|upserted|ed-writer");

        verify(sender, never()).sendOnChannel(eq(wsWriter), eq("documents"), any());
        verify(sender).sendOnChannel(eq(wsOther), eq("documents"), any());
    }

    @Test
    void remoteChanged_selfEcho_isIgnored() throws IOException {
        WebSocketSession ws = wsSession("ws-1");
        registry.subscribe(ws, contextFor("ed-1", "alice", "Alice"), "notes.md");
        org.mockito.Mockito.reset(sender);

        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.changed",
                broadcaster.podIdForTests() + "|" + encodeUrl("notes.md") + "|upserted|");

        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    @Test
    void remoteChanged_noLocalSubscribers_isNoOp() throws IOException {
        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.changed",
                "pod-other|" + encodeUrl("nobody-here.md") + "|upserted|");

        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
    }

    @Test
    void remoteChanged_legacy3PartPayload_stillAccepted() throws IOException {
        // Pods that haven't picked up the editorId-aware payload format
        // still publish 3-part frames. The broadcaster must accept those
        // for the duration of a rolling deploy.
        WebSocketSession ws = wsSession("ws-local");
        registry.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared.md");
        org.mockito.Mockito.reset(sender);

        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.changed",
                "pod-old|" + encodeUrl("shared.md") + "|upserted");

        DocumentChangedNotification pushed = captureChanged(ws);
        assertThat(pushed.getPath()).isEqualTo("shared.md");
    }

    @Test
    void remoteChanged_malformedPayload_isIgnored() throws IOException {
        BiConsumer<String, String> handler = captureRemoteHandler();
        handler.accept("vance:acme:documents.changed", "garbage");
        handler.accept("vance:acme:documents.changed", "one|two");  // < 3 parts

        verify(sender, never()).sendOnChannel(any(), eq("documents"), any());
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

    private static String encodeUrl(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeUrl(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private DocumentChangedNotification captureChanged(WebSocketSession ws) throws IOException {
        ArgumentCaptor<WebSocketEnvelope> cap = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendOnChannel(eq(ws), eq("documents"), cap.capture());
        WebSocketEnvelope match = null;
        for (WebSocketEnvelope env : cap.getAllValues()) {
            if (MessageType.DOCUMENT_CHANGED.equals(env.getType())) match = env;
        }
        assertThat(match).as("expected a DOCUMENT_CHANGED frame to be sent").isNotNull();
        return (DocumentChangedNotification) match.getData();
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<String, String> captureRemoteHandler() {
        ArgumentCaptor<BiConsumer<String, String>> cap =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .subscribeAcrossTenants(eq("documents.changed"), cap.capture());
        return cap.getValue();
    }
}
