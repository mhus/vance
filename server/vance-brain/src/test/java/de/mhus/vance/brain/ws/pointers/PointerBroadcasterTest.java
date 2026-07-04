package de.mhus.vance.brain.ws.pointers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PointerLeaveNotification;
import de.mhus.vance.api.ws.PointerNotification;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class PointerBroadcasterTest {

    private WebSocketSender sender;
    private VanceRedisMessagingService redis;
    private PointerBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        sender = mock(WebSocketSender.class);
        redis = mock(VanceRedisMessagingService.class);
        when(redis.isEnabled()).thenReturn(true);
        broadcaster = new PointerBroadcaster(sender, redis, new ObjectMapper());
        broadcaster.start();
    }

    @Test
    void move_fansOutToOtherSubscribers_skipsSender_andPublishesToRedis() throws IOException {
        WebSocketSession wsSender = wsSession("ws-sender");
        WebSocketSession wsOther = wsSession("ws-other");
        ConnectionContext senderCtx = contextFor("ed-sender", "alice", "Alice");
        broadcaster.subscribe(wsSender, senderCtx, "canvas/board.yaml");
        broadcaster.subscribe(wsOther, contextFor("ed-other", "bob", "Bob"), "canvas/board.yaml");

        broadcaster.move(senderCtx, "canvas/board.yaml", 100.5, 42.0, null);

        // Sender never sees its own pointer.
        verify(sender, never()).sendOnChannel(eq(wsSender), eq("pointers"), any());
        // Other subscriber receives it with full identity + coords.
        PointerNotification pushed = capturePointer(wsOther);
        assertThat(pushed.getPath()).isEqualTo("canvas/board.yaml");
        assertThat(pushed.getEditorId()).isEqualTo("ed-sender");
        assertThat(pushed.getUserId()).isEqualTo("alice");
        assertThat(pushed.getDisplayName()).isEqualTo("Alice");
        assertThat(pushed.getX()).isEqualTo(100.5);
        assertThat(pushed.getY()).isEqualTo(42.0);
        // Cross-pod publish on the move topic.
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(eq("acme"), eq("pointers"), payload.capture());
        String[] parts = payload.getValue().split("\\|", -1);
        assertThat(decodeUrl(parts[1])).isEqualTo("canvas/board.yaml");
        assertThat(parts[2]).isEqualTo("ed-sender");
    }

    @Test
    void move_carriesOptionalDataMap() throws IOException {
        WebSocketSession wsOther = wsSession("ws-other");
        ConnectionContext senderCtx = contextFor("ed-sender", "alice", "Alice");
        broadcaster.subscribe(wsSession("ws-sender"), senderCtx, "board");
        broadcaster.subscribe(wsOther, contextFor("ed-other", "bob", "Bob"), "board");

        broadcaster.move(senderCtx, "board", 1.0, 2.0, Map.of("color", "#e11"));

        PointerNotification pushed = capturePointer(wsOther);
        assertThat(pushed.getData()).containsEntry("color", "#e11");
    }

    @Test
    void unsubscribe_emitsLeaveToRemainingSubscribers_andPublishes() throws IOException {
        WebSocketSession wsLeaver = wsSession("ws-leaver");
        WebSocketSession wsOther = wsSession("ws-other");
        broadcaster.subscribe(wsLeaver, contextFor("ed-leaver", "alice", "Alice"), "board");
        broadcaster.subscribe(wsOther, contextFor("ed-other", "bob", "Bob"), "board");

        broadcaster.unsubscribe(wsLeaver, "board");

        PointerLeaveNotification leave = captureLeave(wsOther);
        assertThat(leave.getPath()).isEqualTo("board");
        assertThat(leave.getEditorId()).isEqualTo("ed-leaver");
        verify(redis).publish(eq("acme"), eq("pointers.leave"), anyString());
    }

    @Test
    void unsubscribeAll_emitsLeaveForEverySubscribedPath() throws IOException {
        WebSocketSession wsLeaver = wsSession("ws-leaver");
        WebSocketSession wsOther = wsSession("ws-other");
        broadcaster.subscribe(wsLeaver, contextFor("ed-leaver", "alice", "Alice"), "board-a");
        broadcaster.subscribe(wsLeaver, contextFor("ed-leaver", "alice", "Alice"), "board-b");
        broadcaster.subscribe(wsOther, contextFor("ed-other", "bob", "Bob"), "board-a");

        broadcaster.unsubscribeAll(wsLeaver);

        PointerLeaveNotification leave = captureLeave(wsOther);
        assertThat(leave.getEditorId()).isEqualTo("ed-leaver");
        assertThat(broadcaster.localSubscribersForTests("board-a")).doesNotContain("ws-leaver");
        assertThat(broadcaster.localSubscribersForTests("board-b")).isEmpty();
    }

    @Test
    void remoteMove_otherPod_pushesToLocalSubscribers() throws IOException {
        WebSocketSession ws = wsSession("ws-local");
        broadcaster.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared");

        BiConsumer<String, String> handler = captureRemoteHandler("pointers");
        handler.accept("vance:acme:pointers",
                "pod-other|" + encodeUrl("shared") + "|ed-remote|7.0|9.0|bob|"
                        + encodeUrl("Bob") + "|");

        PointerNotification pushed = capturePointer(ws);
        assertThat(pushed.getEditorId()).isEqualTo("ed-remote");
        assertThat(pushed.getDisplayName()).isEqualTo("Bob");
        assertThat(pushed.getX()).isEqualTo(7.0);
        assertThat(pushed.getY()).isEqualTo(9.0);
    }

    @Test
    void remoteMove_selfEcho_isIgnored() throws IOException {
        WebSocketSession ws = wsSession("ws-local");
        broadcaster.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared");

        BiConsumer<String, String> handler = captureRemoteHandler("pointers");
        handler.accept("vance:acme:pointers",
                broadcaster.podIdForTests() + "|" + encodeUrl("shared") + "|ed-x|1|2|u||");

        verify(sender, never()).sendOnChannel(any(), eq("pointers"), any());
    }

    @Test
    void remoteMove_noLocalSubscribers_isNoOp() throws IOException {
        BiConsumer<String, String> handler = captureRemoteHandler("pointers");
        handler.accept("vance:acme:pointers",
                "pod-other|" + encodeUrl("nobody") + "|ed-x|1|2|u||");

        verify(sender, never()).sendOnChannel(any(), eq("pointers"), any());
    }

    @Test
    void remoteMove_malformedPayload_isIgnored() throws IOException {
        WebSocketSession ws = wsSession("ws-local");
        broadcaster.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared");

        BiConsumer<String, String> handler = captureRemoteHandler("pointers");
        handler.accept("vance:acme:pointers", "garbage");
        handler.accept("vance:acme:pointers", "a|b|c");  // < 5 parts

        verify(sender, never()).sendOnChannel(any(), eq("pointers"), any());
    }

    @Test
    void remoteLeave_otherPod_pushesLeaveToLocalSubscribers() throws IOException {
        WebSocketSession ws = wsSession("ws-local");
        broadcaster.subscribe(ws, contextFor("ed-local", "alice", "Alice"), "shared");

        BiConsumer<String, String> handler = captureRemoteHandler("pointers.leave");
        handler.accept("vance:acme:pointers.leave",
                "pod-other|" + encodeUrl("shared") + "|ed-gone");

        PointerLeaveNotification leave = captureLeave(ws);
        assertThat(leave.getEditorId()).isEqualTo("ed-gone");
    }

    // ── helpers ────────────────────────────────────────────────

    private static WebSocketSession wsSession(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        return ws;
    }

    private static ConnectionContext contextFor(String editorId, String userId, String displayName) {
        return new ConnectionContext(
                "acme", userId, displayName, "web", "1.0", null, editorId, "10.0.0.1");
    }

    private static String encodeUrl(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeUrl(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private PointerNotification capturePointer(WebSocketSession ws) throws IOException {
        ArgumentCaptor<WebSocketEnvelope> cap = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendOnChannel(eq(ws), eq("pointers"), cap.capture());
        WebSocketEnvelope match = null;
        for (WebSocketEnvelope env : cap.getAllValues()) {
            if (MessageType.POINTER.equals(env.getType())) match = env;
        }
        assertThat(match).as("expected a POINTER frame").isNotNull();
        return (PointerNotification) match.getData();
    }

    private PointerLeaveNotification captureLeave(WebSocketSession ws) throws IOException {
        ArgumentCaptor<WebSocketEnvelope> cap = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendOnChannel(eq(ws), eq("pointers"), cap.capture());
        WebSocketEnvelope match = null;
        for (WebSocketEnvelope env : cap.getAllValues()) {
            if (MessageType.POINTER_LEAVE.equals(env.getType())) match = env;
        }
        assertThat(match).as("expected a POINTER_LEAVE frame").isNotNull();
        return (PointerLeaveNotification) match.getData();
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<String, String> captureRemoteHandler(String channel) {
        ArgumentCaptor<BiConsumer<String, String>> cap =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .subscribeAcrossTenants(eq(channel), cap.capture());
        return cap.getValue();
    }
}
