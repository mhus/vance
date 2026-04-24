package de.mhus.vance.cli.chat;

import de.mhus.vance.api.ws.WebSocketEnvelope;

/**
 * Observer for the WebSocket connection lifecycle.
 *
 * <p>Multiple listeners can be attached to a {@link ConnectionManager}; each
 * receives every event in registration order. Default method bodies are empty
 * so implementations only override what they care about — a metrics collector
 * might only watch state transitions, a heartbeat service only {@link #onStateChanged},
 * and the chat UI all of them.
 *
 * <p>Callbacks are invoked on whatever thread produced the event: connect and
 * login events run on the {@code vance-cli-connect} executor, WebSocket
 * messages arrive on the JDK HttpClient's dispatch thread. Implementations
 * must be thread-safe and must not block for long.
 */
public interface ConnectionLifecycleListener {

    /** Fires whenever {@link ConnectionManager#state()} changes. */
    default void onStateChanged(ConnectionManager.State state) {}

    /** Neutral progress or configuration notice — e.g. "Minted JWT". */
    default void onInfo(String text) {}

    /** Lifecycle checkpoint — e.g. "WebSocket open", "WebSocket closed". */
    default void onSystem(String text) {}

    /** Non-fatal error reportable to the user — e.g. "Connect failed: …". */
    default void onError(String text) {}

    /** Every inbound {@link WebSocketEnvelope} the connection receives. */
    default void onReceived(WebSocketEnvelope envelope) {}
}
