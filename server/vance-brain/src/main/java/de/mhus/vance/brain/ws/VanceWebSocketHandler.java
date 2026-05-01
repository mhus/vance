package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ServerInfo;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.WelcomeData;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.session.SessionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Central dispatcher for incoming WebSocket frames.
 *
 * <p>Frame flow (see {@code specification/websocket-protokoll.md} §2–§5):
 * <ol>
 *   <li>{@link #afterConnectionEstablished(WebSocketSession)} — attach the
 *       session-less {@link ConnectionContext} created by the handshake
 *       interceptor and send {@code welcome}.</li>
 *   <li>{@link #handleTextMessage(WebSocketSession, TextMessage)} — heartbeat
 *       the bound session (if any), parse the envelope, look up the matching
 *       {@link WsHandler}. If a bound session's heartbeat shows that this pod
 *       lost the lease (another pod took over, session was closed), drop the
 *       connection.</li>
 *   <li>{@link #afterConnectionClosed(WebSocketSession, CloseStatus)} — release
 *       the bind via {@link SessionService#unbind(String, String)} so the
 *       session can be resumed later.</li>
 * </ol>
 */
@Component
@Slf4j
public class VanceWebSocketHandler extends TextWebSocketHandler {

    private final SessionService sessionService;
    private final SessionLifecycleService sessionLifecycle;
    private final VanceBrainProperties properties;
    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ClientToolRegistry clientToolRegistry;
    private final SessionConnectionRegistry connectionRegistry;
    private final Map<String, WsHandler> handlers;

    public VanceWebSocketHandler(
            SessionService sessionService,
            SessionLifecycleService sessionLifecycle,
            VanceBrainProperties properties,
            ObjectMapper objectMapper,
            WebSocketSender sender,
            ClientToolRegistry clientToolRegistry,
            SessionConnectionRegistry connectionRegistry,
            List<WsHandler> handlers) {
        this.sessionService = sessionService;
        this.sessionLifecycle = sessionLifecycle;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.clientToolRegistry = clientToolRegistry;
        this.connectionRegistry = connectionRegistry;
        this.handlers = indexHandlers(handlers);
    }

    private static Map<String, WsHandler> indexHandlers(List<WsHandler> handlers) {
        Map<String, WsHandler> index = new HashMap<>();
        for (WsHandler handler : handlers) {
            WsHandler previous = index.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate WsHandler for type '" + handler.type() + "': "
                                + previous.getClass().getName() + " vs. "
                                + handler.getClass().getName());
            }
        }
        return Map.copyOf(index);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        ConnectionContext ctx = resolveContext(wsSession);
        ctx.attach(wsSession);

        WelcomeData welcome = WelcomeData.builder()
                .userId(ctx.getUserId())
                .displayName(ctx.getDisplayName())
                .tenantId(ctx.getTenantId())
                .server(ServerInfo.builder()
                        .version(properties.getServerVersion())
                        .protocolVersion(properties.getProtocolVersion())
                        .pingInterval(properties.getPingIntervalSeconds())
                        .capabilities(properties.getCapabilities())
                        .build())
                .build();

        sender.sendNotification(wsSession, MessageType.WELCOME, welcome);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        ConnectionContext ctx = resolveContext(wsSession);

        if (ctx.hasSession()
                && !sessionService.heartbeat(ctx.getSessionId(), ctx.getConnectionId())) {
            // Another pod took the session over, or it was closed. Drop this connection.
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("Session no longer bound"));
            return;
        }

        WebSocketEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), WebSocketEnvelope.class);
        } catch (Exception parseError) {
            sender.sendError(wsSession, null, 400,
                    "Invalid message envelope: " + parseError.getMessage());
            return;
        }

        String type = envelope.getType();
        WsHandler handler = handlers.get(type);
        if (handler == null) {
            sender.sendError(wsSession, envelope, 400, "Unknown message type: " + type);
            return;
        }
        if (!handler.canExecute(ctx)) {
            sender.sendError(wsSession, envelope, 403,
                    ctx.hasSession()
                            ? "Message type '" + type + "' not allowed in current state"
                            : "Message type '" + type + "' requires a bound session");
            return;
        }

        try {
            handler.handle(ctx, wsSession, envelope);
        } catch (PermissionDeniedException e) {
            log.debug("permission denied on '{}': {}", type, e.getMessage());
            sender.sendError(wsSession, envelope, 403, "permission_denied");
        } catch (RuntimeException e) {
            log.warn("Handler for '{}' failed", type, e);
            sender.sendError(wsSession, envelope, 500, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        Object attr = wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        if (!(attr instanceof ConnectionContext ctx)) return;
        if (ctx.hasSession()) {
            String sessionId = ctx.getSessionId();
            clientToolRegistry.unregister(sessionId);
            connectionRegistry.unregister(sessionId);
            sessionService.unbind(sessionId, ctx.getConnectionId());
            // Drive the per-session onDisconnect policy AFTER the connection
            // bookkeeping has been cleared. The lifecycle service may
            // suspend / close the session; engines on the lane finish at
            // the next safe boundary.
            try {
                sessionLifecycle.onDisconnect(sessionId);
            } catch (RuntimeException e) {
                log.warn("onDisconnect dispatch failed for session='{}'", sessionId, e);
            }
        }
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
