package de.mhus.vance.cli.chat;

/**
 * Observer for the session-binding lifecycle on top of a live connection.
 *
 * <p>A session only exists inside a connection — when the connection drops,
 * the session is implicitly unbound and {@link #onSessionUnbound()} fires.
 * Multiple listeners can be attached to a {@link ConnectionManager}.
 *
 * <p>Like {@link ConnectionLifecycleListener}, callbacks are invoked on
 * whatever thread produced the event. Implementations must be thread-safe.
 */
public interface SessionLifecycleListener {

    /** A session has just been bound (via {@code session.create} or {@code session.resume}). */
    default void onSessionBound(String sessionId, String projectId) {}

    /** The previously bound session is no longer attached to this connection. */
    default void onSessionUnbound() {}
}
