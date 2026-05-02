package de.mhus.vance.brain.enginemessage;

import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Server side of the cross-pod {@code /internal/engine-bind} WebSocket.
 * Receives {@link EngineMessageDocument} frames pushed by another brain
 * process, persists them into the local inbox via
 * {@link EngineMessageService#acceptDelivery}, schedules a lane turn on
 * the target process, and acks back to the sender.
 *
 * <p>One frame in, one ack out. No connection-level state — every frame
 * is independent and idempotent through the {@code messageId}, so
 * disconnect/reconnect is a non-event.
 *
 * <p>Inbound frame: a JSON-serialised {@link EngineMessageDocument}.
 * Outbound frame: a JSON-serialised {@link EngineWsAck}.
 *
 * <p>Auth is upstream (handshake interceptor); this handler trusts that
 * any session reaching it carries a verified internal token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EngineWsServerHandler extends TextWebSocketHandler {

    private final EngineMessageService engineMessageService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("engine-bind WS opened: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        EngineMessageDocument incoming;
        try {
            incoming = objectMapper.readValue(payload, EngineMessageDocument.class);
        } catch (RuntimeException e) {
            log.warn("engine-bind WS: malformed frame on session {}: {}", session.getId(), e.toString());
            sendAck(session, EngineWsAck.error(extractMessageId(payload), "malformed frame: " + e.toString()));
            return;
        }
        if (incoming.getMessageId() == null || incoming.getMessageId().isBlank()) {
            sendAck(session, EngineWsAck.error("", "messageId is required"));
            return;
        }

        try {
            engineMessageService.acceptDelivery(incoming);
        } catch (RuntimeException e) {
            log.warn("engine-bind WS: acceptDelivery failed for messageId={}: {}",
                    incoming.getMessageId(), e.toString());
            sendAck(session, EngineWsAck.error(incoming.getMessageId(), "acceptDelivery: " + e.toString()));
            return;
        }

        // Wake the receiver's lane so it drains the new inbox entry. Same
        // pattern as same-pod appendPending; for cross-pod arrivals this is
        // what turns mongo persistence into engine action on this pod.
        if (incoming.getTargetProcessId() != null && !incoming.getTargetProcessId().isBlank()) {
            eventEmitter.scheduleTurn(incoming.getTargetProcessId());
        }

        sendAck(session, EngineWsAck.ack(incoming.getMessageId()));
        log.debug("engine-bind WS delivered messageId={} target={} sender={}",
                incoming.getMessageId(), incoming.getTargetProcessId(), incoming.getSenderProcessId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("engine-bind WS closed: {} status={}", session.getId(), status);
    }

    private void sendAck(WebSocketSession session, EngineWsAck ack) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
        } catch (Exception e) {
            log.warn("engine-bind WS: failed to send ack for messageId={}: {}",
                    ack.messageId(), e.toString());
        }
    }

    /** Best-effort messageId scrape from a malformed frame for the error ack. */
    private @Nullable String extractMessageId(String payload) {
        try {
            return objectMapper.readTree(payload).path("messageId").asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
