package de.mhus.vance.api.ws.client;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import org.jspecify.annotations.Nullable;

/**
 * Callback contract for {@link VanceWebSocketClient}.
 *
 * All methods have default no-op implementations so callers only need to
 * override what they care about.
 */
public interface VanceWebSocketClientListener {

    /** Called once the TCP/HTTP handshake has succeeded and the channel is open. */
    default void onOpen() {
    }

    /**
     * Called for every fully-assembled text frame received from the server.
     * The envelope has been parsed; {@code data} is a raw Map/List/primitive tree
     * that the caller should {@code convertValue(...)} to the typed DTO matching
     * {@code envelope.getType()}.
     */
    default void onMessage(WebSocketEnvelope envelope) {
    }

    /**
     * Called when the connection closes, whether by local request, peer close,
     * or transport error.
     *
     * @param statusCode WebSocket close code (1000 = normal, 1006 = abnormal)
     * @param reason     Optional close reason from the peer
     */
    default void onClose(int statusCode, @Nullable String reason) {
    }

    /** Called on any transport or parsing error. */
    default void onError(Throwable error) {
    }
}
