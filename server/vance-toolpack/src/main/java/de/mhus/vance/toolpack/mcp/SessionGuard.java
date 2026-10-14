package de.mhus.vance.toolpack.mcp;

import org.jspecify.annotations.Nullable;

/**
 * Host-provided lifecycle hooks for one {@link McpConnection} session —
 * the seam through which the brain attaches its resource-lease system
 * without dragging Mongo or Spring into vance-toolpack.
 *
 * <p>Call order (see {@code McpConnection}):
 * <ol>
 *   <li>{@link #onSessionOpen()} — before the initialize handshake; the
 *       brain acquires the lease here. Throwing aborts the session —
 *       a busy lease must not produce a half-initialized connection.</li>
 *   <li>{@link #onSessionId(String)} — after a successful initialize, with
 *       the server-assigned session id (payload data; may stay null for
 *       servers without sessions).</li>
 *   <li>{@link #onSessionUse()} — at every {@code tools/call}; the brain's
 *       check-at-use: verifies the lease is still held and touches the
 *       TTL clock. A {@link McpSessionRevokedException} closes the
 *       session; the next call opens a new one.</li>
 *   <li>{@link #onSessionClose()} — on {@code close()}, regular or failed
 *       init; the brain releases the lease.</li>
 * </ol>
 *
 * <p>Implementations live in the host (brain: lease-backed; foot: absent —
 * {@code null} guard, all hooks skipped). The interface lives in toolpack
 * because {@link McpConnection} owns the session lifecycle and must not
 * depend on brain classes.
 */
public interface SessionGuard {

    /** Before the initialize handshake — acquire (throwing aborts the session). */
    default void onSessionOpen() {}

    /** After initialize, with the server session id if the server assigned one. */
    default void onSessionId(@Nullable String sessionId) {}

    /**
     * At every tools/call — usage check + TTL touch.
     *
     * @throws McpSessionRevokedException when the session's claim is gone
     */
    default void onSessionUse() {}

    /** On close — release. */
    default void onSessionClose() {}

    /**
     * Identity of the claim the current session holds ({@code null} when
     * none is held) — lets the host match a revocation to the session it
     * was issued for, so a late revocation never closes a newer session.
     */
    default @Nullable String sessionToken() {
        return null;
    }
}
