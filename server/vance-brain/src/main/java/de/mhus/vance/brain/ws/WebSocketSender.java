package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared sink for outbound WebSocket frames — encodes {@link WebSocketEnvelope}s
 * to JSON and writes them to the {@link WebSocketSession}.
 *
 * <p>Keeping the JSON mapper in one place means handlers stay free of
 * serialization concerns and the reply/notification/error shapes stay
 * consistent. See {@code specification/websocket-protokoll.md} §1 for the
 * envelope contract.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSender {

    private final ObjectMapper objectMapper;

    /** Sends a response to an incoming request — echoes {@code replyTo}. */
    public void sendReply(
            WebSocketSession wsSession,
            WebSocketEnvelope request,
            String type,
            @Nullable Object data) throws IOException {
        String replyTo = request.getId();
        WebSocketEnvelope response = replyTo != null
                ? WebSocketEnvelope.reply(replyTo, type, data)
                : WebSocketEnvelope.notification(type, data);
        send(wsSession, response);
    }

    /** Sends an {@link MessageType#ERROR} reply to an incoming request. */
    public void sendError(
            WebSocketSession wsSession,
            @Nullable WebSocketEnvelope request,
            int code,
            String message) throws IOException {
        ErrorData data = ErrorData.builder().errorCode(code).errorMessage(message).build();
        String replyTo = request == null ? null : request.getId();
        WebSocketEnvelope envelope = replyTo != null
                ? WebSocketEnvelope.reply(replyTo, MessageType.ERROR, data)
                : WebSocketEnvelope.notification(MessageType.ERROR, data);
        send(wsSession, envelope);
    }

    /** Server-initiated push — no {@code replyTo}. */
    public void sendNotification(WebSocketSession wsSession, String type, @Nullable Object data)
            throws IOException {
        send(wsSession, WebSocketEnvelope.notification(type, data));
    }

    /**
     * Low-level write. Handlers normally use one of the shaped helpers above.
     *
     * <p>{@link WebSocketSession#sendMessage} is not thread-safe per Spring's
     * contract; with async engine dispatch (steer worker thread, langchain4j
     * streaming-callback threads, the WS receive thread) several writers can
     * race on the same session and produce interleaved frames. The
     * synchronisation below serialises every outbound frame per session,
     * which is the cheap, correct fix for the few KHz of writes we see.
     */
    public void send(WebSocketSession wsSession, WebSocketEnvelope envelope) throws IOException {
        String json = objectMapper.writeValueAsString(envelope);
        synchronized (wsSession) {
            wsSession.sendMessage(new TextMessage(json));
        }
    }
}
