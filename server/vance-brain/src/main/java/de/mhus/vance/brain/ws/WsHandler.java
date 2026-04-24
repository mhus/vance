package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.io.IOException;
import org.springframework.web.socket.WebSocketSession;

/**
 * Handles one WebSocket message type dispatched by
 * {@link VanceWebSocketHandler}.
 *
 * <p>Every handler is a Spring bean and is discovered via constructor-injected
 * {@code List<WsHandler>}. The dispatcher builds a {@code type() -> handler}
 * map at startup and fails fast on duplicate types.
 *
 * <h4>Session gating</h4>
 *
 * {@link #canExecute(ConnectionContext)} is consulted before
 * {@link #handle(ConnectionContext, WebSocketSession, WebSocketEnvelope) handle}.
 * Default: a session must be bound — most domain commands require it. Pre-session
 * handlers ({@code session.list}, {@code session.create}, {@code session.resume},
 * {@code project.list}, {@code projectgroup.list}) override and return {@code true}.
 * Bind-shift handlers ({@code session.create}, {@code session.resume}) override
 * and return {@code !ctx.hasSession()} so they can't be invoked on a connection
 * that already owns a session.
 */
public interface WsHandler {

    /**
     * The {@code type} value of the envelopes this handler consumes — matches a
     * {@link de.mhus.vance.api.ws.MessageType} constant.
     */
    String type();

    /**
     * Handles a matching envelope. Implementations own their own error mapping
     * and reply via the shared {@link WebSocketSender}.
     */
    void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException;

    /**
     * Precondition check. Defaults to "session required". Return {@code false}
     * to make the dispatcher reply with a {@code 403 session.required} error
     * instead of invoking {@link #handle}.
     */
    default boolean canExecute(ConnectionContext ctx) {
        return ctx.hasSession();
    }
}
