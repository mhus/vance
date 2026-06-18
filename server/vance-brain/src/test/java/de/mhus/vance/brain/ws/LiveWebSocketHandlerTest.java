package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.live.HomePodLookupService;
import de.mhus.vance.brain.ws.live.HomePodTarget;
import de.mhus.vance.brain.ws.live.LiveChatTunnelRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the channel demux + payload-unwrap of the new
 * {@code /brain/{tenant}/ws} endpoint: only {@code channel="session"}
 * is accepted in v1, payloads are converted to {@link WebSocketEnvelope}
 * and handed to the underlying {@link VanceWebSocketHandler#dispatch}.
 */
class LiveWebSocketHandlerTest {

    private VanceWebSocketHandler chatHandler;
    private WebSocketSender sender;
    private ObjectMapper objectMapper;
    private HomePodLookupService homePodLookup;
    private LiveChatTunnelRegistry tunnelRegistry;
    private LiveWebSocketHandler handler;

    private WebSocketSession wsSession;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        chatHandler = mock(VanceWebSocketHandler.class);
        sender = mock(WebSocketSender.class);
        objectMapper = new ObjectMapper();
        homePodLookup = mock(HomePodLookupService.class);
        tunnelRegistry = mock(LiveChatTunnelRegistry.class);
        // Default: every lookup resolves to LOCAL so the routing test stays
        // focused on the envelope-demux behaviour. Cross-pod routing has its
        // own dedicated tests.
        when(homePodLookup.resolve(any(), any(), any())).thenReturn(HomePodTarget.LOCAL);
        handler = new LiveWebSocketHandler(
                chatHandler, objectMapper, sender, homePodLookup, tunnelRegistry);

        wsSession = mock(WebSocketSession.class);
        ctx = new ConnectionContext(
                "acme", "alice", null, "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
        when(wsSession.getAttributes()).thenReturn(Map.of(
                VanceHandshakeInterceptor.ATTR_CONNECTION, ctx));
    }

    @Test
    void sessionChannel_localTarget_unwrapsAndDispatchesLocally() throws Exception {
        WebSocketEnvelope inner = WebSocketEnvelope.request("req-1", "ping", null);
        LiveEnvelope outer = new LiveEnvelope("session", "sess-42", inner);

        handler.handleTextMessage(wsSession, frame(outer));

        ArgumentCaptor<WebSocketEnvelope> captor = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(chatHandler).dispatch(eq(wsSession), eq(ctx), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("ping");
        assertThat(captor.getValue().getId()).isEqualTo("req-1");
        verify(sender, never()).sendError(any(), any(), eq(400), any());
    }

    @Test
    void sessionChannel_remoteTarget_forwardsThroughTunnel_andSkipsLocalDispatch()
            throws Exception {
        WebSocketEnvelope inner = WebSocketEnvelope.request("req-1", "ping", null);
        LiveEnvelope outer = new LiveEnvelope("session", "sess-42", inner);
        when(homePodLookup.resolve(any(), any(), any()))
                .thenReturn(HomePodTarget.remote("home-pod-7:8080"));
        de.mhus.vance.brain.ws.live.LiveChatTunnel tunnel =
                mock(de.mhus.vance.brain.ws.live.LiveChatTunnel.class);
        when(tunnelRegistry.getOrOpen(eq(wsSession), eq(ctx), eq("home-pod-7:8080")))
                .thenReturn(tunnel);

        handler.handleTextMessage(wsSession, frame(outer));

        ArgumentCaptor<WebSocketEnvelope> sent = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(tunnel).send(sent.capture());
        assertThat(sent.getValue().getType()).isEqualTo("ping");
        assertThat(sent.getValue().getId()).isEqualTo("req-1");
        verify(chatHandler, never()).dispatch(any(), any(), any());
        verify(sender, never()).sendError(any(), any(), eq(400), any());
    }

    @Test
    void sessionChannel_tunnelOpenFails_yields502Error_andNoLocalFallback()
            throws Exception {
        WebSocketEnvelope inner = WebSocketEnvelope.request("req-1", "ping", null);
        LiveEnvelope outer = new LiveEnvelope("session", "sess-42", inner);
        when(homePodLookup.resolve(any(), any(), any()))
                .thenReturn(HomePodTarget.remote("home-pod-7:8080"));
        when(tunnelRegistry.getOrOpen(any(), any(), any()))
                .thenThrow(new RuntimeException("connect refused"));

        handler.handleTextMessage(wsSession, frame(outer));

        verify(sender).sendError(eq(wsSession), any(WebSocketEnvelope.class), eq(502),
                org.mockito.ArgumentMatchers.contains("Home-pod tunnel"));
        verify(chatHandler, never()).dispatch(any(), any(), any());
    }

    @Test
    void missingChannel_yields400Error_andNoDispatch() throws Exception {
        LiveEnvelope outer = new LiveEnvelope("", null,
                WebSocketEnvelope.request("req-1", "ping", null));

        handler.handleTextMessage(wsSession, frame(outer));

        verify(sender).sendError(eq(wsSession), isNull(), eq(400), contains("channel"));
        verify(chatHandler, never()).dispatch(any(), any(), any());
    }

    @Test
    void unsupportedChannel_yields400Error_andNoDispatch() throws Exception {
        LiveEnvelope outer = new LiveEnvelope("documents", null, Map.of("path", "x.md"));

        handler.handleTextMessage(wsSession, frame(outer));

        verify(sender).sendError(eq(wsSession), isNull(), eq(400),
                contains("not supported"));
        verify(chatHandler, never()).dispatch(any(), any(), any());
    }

    @Test
    void sessionChannel_missingPayload_yields400Error_andNoDispatch() throws Exception {
        LiveEnvelope outer = new LiveEnvelope("session", "sess-1", null);

        handler.handleTextMessage(wsSession, frame(outer));

        verify(sender).sendError(eq(wsSession), isNull(), eq(400), contains("payload"));
        verify(chatHandler, never()).dispatch(any(), any(), any());
    }

    @Test
    void malformedJson_yields400Error_andNoDispatch() throws Exception {
        TextMessage broken = new TextMessage("{this is not json");

        handler.handleTextMessage(wsSession, broken);

        verify(sender).sendError(eq(wsSession), isNull(), eq(400),
                contains("Invalid live envelope"));
        verify(chatHandler, never()).dispatch(any(), any(), any());
    }

    private TextMessage frame(LiveEnvelope envelope) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(envelope));
    }
}
