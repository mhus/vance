package de.mhus.vance.foot.connection;

import de.mhus.vance.api.ws.WebSocketEnvelope;

/**
 * Handles incoming WebSocket envelopes of one specific {@code type}. Implementations
 * are picked up automatically by {@link MessageDispatcher} via Spring's bean discovery.
 *
 * <p>{@link #messageType()} returns the {@link de.mhus.vance.api.ws.MessageType}
 * constant the handler is interested in. Exactly one handler per type is allowed —
 * the dispatcher fails fast on duplicates so collisions are caught at startup.
 */
public interface MessageHandler {

    /** The {@link de.mhus.vance.api.ws.MessageType} this handler is responsible for. */
    String messageType();

    /**
     * Handle an inbound envelope. The dispatcher catches and logs exceptions
     * so a misbehaving handler does not tear down the receive loop.
     */
    void handle(WebSocketEnvelope envelope);
}
