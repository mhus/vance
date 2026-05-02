package de.mhus.vance.brain.enginemessage;

import org.jspecify.annotations.Nullable;

/**
 * Server→client acknowledgement frame on the {@code /internal/engine-bind}
 * WebSocket. Sent in response to every inbound EngineMessage push.
 *
 * <p>{@code status="ack"} means the message has been durably accepted into
 * the receiver's inbox (deliveredAt set, idempotent on duplicates).
 * {@code status="error"} carries a {@code reason} and the sender should
 * keep the message in its outbox for replay on the next reconnect.
 */
public record EngineWsAck(String messageId, String status, @Nullable String reason) {

    public static final String STATUS_ACK = "ack";
    public static final String STATUS_ERROR = "error";

    public static EngineWsAck ack(String messageId) {
        return new EngineWsAck(messageId, STATUS_ACK, null);
    }

    public static EngineWsAck error(String messageId, String reason) {
        return new EngineWsAck(messageId, STATUS_ERROR, reason);
    }
}
