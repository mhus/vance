package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.live.HomePodLookupService;
import de.mhus.vance.brain.ws.live.HomePodTarget;
import de.mhus.vance.brain.ws.live.LiveChatTunnel;
import de.mhus.vance.brain.ws.live.LiveChatTunnelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket handler for the new multi-channel Live-WS endpoint
 * {@code /brain/{tenant}/ws/live}.
 *
 * <p>v1 only handles the {@code session} channel. The inner
 * {@link WebSocketEnvelope} payload is extracted, routed by
 * {@link HomePodLookupService} to the project's home-pod, and either
 * dispatched locally (via {@link VanceWebSocketHandler#dispatch}) or
 * forwarded through a {@link LiveChatTunnel} to the remote home-pod's
 * {@code /internal/{tenant}/ws/chat} endpoint.
 *
 * <p>Connection lifecycle (welcome on open, cleanup on close) is delegated
 * to {@link VanceWebSocketHandler}. The Face-Pod also tears down any open
 * tunnel for this connection on close.
 *
 * <p>Channels other than {@code session} ({@code documents}, {@code notify},
 * {@code progress}, {@code control}) are reserved at the protocol level and
 * rejected by this handler — see {@code planning/live-ws.md} §6.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveWebSocketHandler extends TextWebSocketHandler {

    /**
     * WebSocket-session attribute marker: when present and {@code true}, the
     * connection speaks the {@link de.mhus.vance.api.ws.LiveEnvelope}-wrapped
     * protocol. {@link WebSocketSender} reads this flag to decide whether to
     * wrap outgoing frames.
     */
    public static final String ATTR_LIVE_PROTOCOL = "vance.live-protocol";

    private static final String CHANNEL_SESSION = "session";

    private final VanceWebSocketHandler chatHandler;
    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final HomePodLookupService homePodLookup;
    private final LiveChatTunnelRegistry tunnelRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        // Mark the connection so WebSocketSender wraps outgoing frames in a
        // LiveEnvelope. Must happen before chatHandler.afterConnectionEstablished
        // because that already sends the welcome through WebSocketSender.
        wsSession.getAttributes().put(ATTR_LIVE_PROTOCOL, Boolean.TRUE);
        chatHandler.afterConnectionEstablished(wsSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message)
            throws Exception {
        ConnectionContext ctx = resolveContext(wsSession);
        LiveEnvelope live;
        try {
            live = objectMapper.readValue(message.getPayload(), LiveEnvelope.class);
        } catch (Exception parseError) {
            sender.sendError(wsSession, null, 400,
                    "Invalid live envelope: " + parseError.getMessage());
            return;
        }

        String channel = live.getChannel();
        if (channel == null || channel.isBlank()) {
            sender.sendError(wsSession, null, 400, "Live envelope missing 'channel' field");
            return;
        }
        if (!CHANNEL_SESSION.equals(channel)) {
            sender.sendError(wsSession, null, 400,
                    "Channel not supported in v1: '" + channel + "'");
            return;
        }

        if (live.getPayload() == null) {
            sender.sendError(wsSession, null, 400,
                    "session-channel frame missing 'payload'");
            return;
        }
        WebSocketEnvelope inner;
        try {
            inner = objectMapper.convertValue(live.getPayload(), WebSocketEnvelope.class);
        } catch (Exception convertError) {
            sender.sendError(wsSession, null, 400,
                    "Invalid session-channel payload: " + convertError.getMessage());
            return;
        }

        HomePodTarget target = homePodLookup.resolve(ctx, live.getSessionId(), inner);
        if (target.local()) {
            chatHandler.dispatch(wsSession, ctx, inner);
            return;
        }

        try {
            LiveChatTunnel tunnel =
                    tunnelRegistry.getOrOpen(wsSession, ctx, target.requireEndpoint());
            tunnel.send(inner);
        } catch (Exception tunnelError) {
            log.warn("Live-WS tunnel forward failed for type='{}' to home='{}': {}",
                    inner.getType(), target.endpoint(), tunnelError.toString());
            sender.sendError(wsSession, inner, 502,
                    "Home-pod tunnel unavailable: " + tunnelError.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        try {
            tunnelRegistry.closeFor(wsSession);
        } catch (RuntimeException e) {
            log.warn("LiveChatTunnel close for external='{}' failed: {}",
                    wsSession.getId(), e.toString());
        }
        chatHandler.afterConnectionClosed(wsSession, status);
    }

    private static ConnectionContext resolveContext(WebSocketSession wsSession) {
        Object attr = wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        if (!(attr instanceof ConnectionContext ctx)) {
            throw new IllegalStateException(
                    "No ConnectionContext attached — handshake interceptor did not run");
        }
        return ctx;
    }
}
