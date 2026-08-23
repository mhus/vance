package de.mhus.vance.brain.ws.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.LiveChannels;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.RemoteAttachRequest;
import de.mhus.vance.api.ws.RemoteClientAnnounce;
import de.mhus.vance.api.ws.RemoteClientRoster;
import de.mhus.vance.api.ws.RemoteInputRequest;
import de.mhus.vance.api.ws.RemoteOutputBatch;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Frame demux and — the part worth guarding — the two trust rules: a client's
 * identity comes from its socket binding, and a watcher may only reach a client
 * its own user owns.
 */
class RemoteClientChannelHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RemoteClientRegistry registry;
    private RemoteControlRelay relay;
    private WebSocketSender sender;
    private RemoteClientChannelHandler handler;

    @BeforeEach
    void setUp() {
        VanceRedisMessagingService redis = mock(VanceRedisMessagingService.class);
        when(redis.isEnabled()).thenReturn(false);
        registry = new RemoteClientRegistry(redis, objectMapper);
        relay = mock(RemoteControlRelay.class);
        sender = mock(WebSocketSender.class);
        handler = new RemoteClientChannelHandler(registry, relay, sender, objectMapper);
    }

    private static WebSocketSession ws(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private static ConnectionContext ctx(String user) {
        return new ConnectionContext("acme", user, null, "default", "1.0", "vance-foot",
                "editor-" + user, "10.0.0.1");
    }

    private LiveEnvelope frame(String type, Object data) {
        return new LiveEnvelope(LiveChannels.CLIENTS, null,
                objectMapper.convertValue(WebSocketEnvelope.notification(type, data), Object.class));
    }

    private void announceAlice(WebSocketSession session) throws Exception {
        handler.handle(session, ctx("alice"), frame(MessageType.CLIENT_ANNOUNCE,
                RemoteClientAnnounce.builder().clientId("fc_1").label("mba").pid(1).build()));
    }

    @Test
    void announce_registersTheClient() throws Exception {
        announceAlice(ws("c1"));

        assertThat(registry.findLocal("fc_1")).isNotNull();
    }

    @Test
    void announce_withoutClientId_isRejected() throws Exception {
        handler.handle(ws("c1"), ctx("alice"), frame(MessageType.CLIENT_ANNOUNCE,
                RemoteClientAnnounce.builder().label("no id").build()));

        assertThat(errorCode()).isEqualTo(400);
    }

    @Test
    void clientOutput_isAttributedToTheSocketNotThePayload() throws Exception {
        WebSocketSession client = ws("c1");
        announceAlice(client);

        // Payload claims a different client — the relay must still be told the
        // id bound to this socket, or output could be forged onto another
        // client's watchers.
        handler.handle(client, ctx("alice"), frame(MessageType.CLIENT_OUTPUT,
                RemoteOutputBatch.builder().clientId("fc_impostor").lines(java.util.List.of()).build()));

        verify(relay).toWatchers(eq("acme"), eq("alice"), eq("fc_1"), any());
        verify(relay, never()).toWatchers(any(), any(), eq("fc_impostor"), any());
    }

    @Test
    void clientOutput_fromAnUnannouncedConnectionIsDropped() throws Exception {
        handler.handle(ws("stranger"), ctx("alice"), frame(MessageType.CLIENT_OUTPUT,
                RemoteOutputBatch.builder().clientId("fc_1").lines(java.util.List.of()).build()));

        verify(relay, never()).toWatchers(any(), any(), any(), any());
    }

    @Test
    void list_answersWithTheOwnRoster() throws Exception {
        announceAlice(ws("c1"));

        handler.handle(ws("watcher"), ctx("alice"), frame(MessageType.CLIENT_LIST, null));

        RemoteClientRoster roster = captureReply(RemoteClientRoster.class, MessageType.CLIENT_ROSTER);
        assertThat(roster.getClients()).extracting(i -> i.getClientId()).containsExactly("fc_1");
        assertThat(roster.isCrossPod()).isFalse();
    }

    @Test
    void attach_onOwnClient_registersWatcherAndForwards() throws Exception {
        announceAlice(ws("c1"));
        WebSocketSession watcher = ws("watcher");

        handler.handle(watcher, ctx("alice"),
                frame(MessageType.CLIENT_ATTACH,
                        RemoteAttachRequest.builder().clientId("fc_1").sinceSeq(7).build()));

        verify(relay).attachWatcher(eq(watcher), any(), eq("fc_1"));
        verify(relay).toClient(eq("acme"), eq("alice"), eq("fc_1"), any());
    }

    @Test
    void attach_onForeignClient_isRefusedAsNotFound() throws Exception {
        announceAlice(ws("c1"));

        handler.handle(ws("watcher"), ctx("bob"),
                frame(MessageType.CLIENT_ATTACH,
                        RemoteAttachRequest.builder().clientId("fc_1").build()));

        // 404, not 403: the existence of someone else's machine is not
        // information this channel owes anyone.
        assertThat(errorCode()).isEqualTo(404);
        verify(relay, never()).attachWatcher(any(), any(), any());
        verify(relay, never()).toClient(any(), any(), any(), any());
    }

    @Test
    void input_onForeignClient_isRefused() throws Exception {
        announceAlice(ws("c1"));

        handler.handle(ws("watcher"), ctx("bob"), frame(MessageType.CLIENT_INPUT,
                RemoteInputRequest.builder().clientId("fc_1").line("rm -rf /").build()));

        assertThat(errorCode()).isEqualTo(404);
        verify(relay, never()).toClient(any(), any(), any(), any());
    }

    @Test
    void input_onOwnClient_isForwarded() throws Exception {
        announceAlice(ws("c1"));

        handler.handle(ws("watcher"), ctx("alice"), frame(MessageType.CLIENT_INPUT,
                RemoteInputRequest.builder().clientId("fc_1").line("/status").build()));

        verify(relay).toClient(eq("acme"), eq("alice"), eq("fc_1"), any());
    }

    @Test
    void unknownType_isRejected() throws Exception {
        handler.handle(ws("c1"), ctx("alice"), frame("client-does-not-exist", null));

        assertThat(errorCode()).isEqualTo(400);
    }

    @Test
    void missingPayload_isRejected() throws Exception {
        handler.handle(ws("c1"), ctx("alice"), new LiveEnvelope(LiveChannels.CLIENTS, null, null));

        assertThat(errorCode()).isEqualTo(400);
    }

    @Test
    void forgetConnection_dropsBothRoles() throws Exception {
        WebSocketSession session = ws("c1");
        announceAlice(session);

        handler.forgetConnection(session);

        verify(relay).detachAll(session);
        assertThat(registry.findLocal("fc_1")).isNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private int errorCode() throws Exception {
        ErrorData err = captureReply(ErrorData.class, MessageType.ERROR);
        return err.getErrorCode();
    }

    private <T> T captureReply(Class<T> type, String expectedType) throws Exception {
        ArgumentCaptor<WebSocketEnvelope> captor = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(sender).sendOnChannel(any(), eq(LiveChannels.CLIENTS), captor.capture());
        WebSocketEnvelope envelope = captor.getValue();
        assertThat(envelope.getType()).isEqualTo(expectedType);
        return objectMapper.convertValue(envelope.getData(), type);
    }
}
