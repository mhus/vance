package de.mhus.vance.brain.ws;

import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.api.ws.ServerInfo;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.WelcomeData;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Central dispatcher for incoming WebSocket frames.
 *
 * Frame flow (see {@code specification/websocket-protokoll.md} §2–§5):
 * <ol>
 *   <li>{@link #afterConnectionEstablished(WebSocketSession)} — bind the
 *       {@link ClientSession} that the handshake interceptor attached, send
 *       {@code welcome}</li>
 *   <li>{@link #handleTextMessage(WebSocketSession, TextMessage)} — parse the
 *       envelope, route by {@code type}</li>
 *   <li>{@link #afterConnectionClosed(WebSocketSession, CloseStatus)} — unbind
 *       the connection (the session itself lives on and can be resumed)</li>
 * </ol>
 */
@Component
public class VanceWebSocketHandler extends TextWebSocketHandler {

    private final ClientSessionRegistry registry;
    private final VanceBrainProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public VanceWebSocketHandler(
            ClientSessionRegistry registry,
            VanceBrainProperties properties,
            ObjectMapper objectMapper) {
        this(registry, properties, objectMapper, Clock.systemUTC());
    }

    public VanceWebSocketHandler(
            ClientSessionRegistry registry,
            VanceBrainProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        ClientSession clientSession = resolveSession(wsSession);
        boolean resumed = Boolean.TRUE.equals(wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_RESUMED));

        if (!clientSession.bindConnection(wsSession)) {
            wsSession.close(CloseStatus.SERVER_ERROR.withReason("Session already bound"));
            return;
        }

        WelcomeData welcome = WelcomeData.builder()
                .sessionId(clientSession.getSessionId())
                .sessionResumed(resumed)
                .userId(clientSession.getUserId())
                .displayName(clientSession.getDisplayName())
                .tenantId(clientSession.getTenantId())
                .server(ServerInfo.builder()
                        .version(properties.getServerVersion())
                        .protocolVersion(properties.getProtocolVersion())
                        .pingInterval(properties.getPingIntervalSeconds())
                        .capabilities(properties.getCapabilities())
                        .build())
                .build();

        send(wsSession, WebSocketEnvelope.notification(MessageType.WELCOME, welcome));
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        ClientSession clientSession = resolveSession(wsSession);
        clientSession.touch();

        WebSocketEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), WebSocketEnvelope.class);
        } catch (Exception parseError) {
            sendError(wsSession, null, 400, "Invalid message envelope: " + parseError.getMessage());
            return;
        }

        String type = envelope.getType();
        switch (type) {
            case MessageType.PING -> handlePing(wsSession, envelope);
            case MessageType.LOGOUT -> handleLogout(wsSession, clientSession);
            default -> sendError(wsSession, envelope.getId(), 400, "Unknown message type: " + type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        Object attr = wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_SESSION);
        if (attr instanceof ClientSession clientSession) {
            clientSession.unbindConnection(wsSession);
        }
    }

    private void handlePing(WebSocketSession wsSession, WebSocketEnvelope envelope) throws IOException {
        PingData ping = objectMapper.convertValue(envelope.getData(), PingData.class);
        PongData pong = PongData.builder()
                .clientTimestamp(ping.getClientTimestamp())
                .serverTimestamp(Instant.now(clock).toEpochMilli())
                .build();
        String replyTo = envelope.getId();
        WebSocketEnvelope response = replyTo != null
                ? WebSocketEnvelope.reply(replyTo, MessageType.PONG, pong)
                : WebSocketEnvelope.notification(MessageType.PONG, pong);
        send(wsSession, response);
    }

    private void handleLogout(WebSocketSession wsSession, ClientSession clientSession) throws IOException {
        registry.remove(clientSession.getSessionId());
        clientSession.unbindConnection(wsSession);
        wsSession.close(CloseStatus.NORMAL);
    }

    private void sendError(WebSocketSession wsSession, @Nullable String replyTo, int code, String message) throws IOException {
        ErrorData data = ErrorData.builder().errorCode(code).errorMessage(message).build();
        WebSocketEnvelope envelope = replyTo != null
                ? WebSocketEnvelope.reply(replyTo, MessageType.ERROR, data)
                : WebSocketEnvelope.notification(MessageType.ERROR, data);
        send(wsSession, envelope);
    }

    private void send(WebSocketSession wsSession, WebSocketEnvelope envelope) throws IOException {
        String json = objectMapper.writeValueAsString(envelope);
        wsSession.sendMessage(new TextMessage(json));
    }

    private static ClientSession resolveSession(WebSocketSession wsSession) {
        Object attr = wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_SESSION);
        if (!(attr instanceof ClientSession clientSession)) {
            throw new IllegalStateException(
                    "No ClientSession attached — handshake interceptor did not run");
        }
        return clientSession;
    }
}
