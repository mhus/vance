package de.mhus.vance.toolpack.mcp;

/**
 * Thrown by a {@link SessionGuard} when the session's claim is gone — in
 * the brain: the resource lease was revoked (idle or pod reap). The
 * {@link McpConnection} closes the session on it, so the next call opens
 * a fresh one (and acquires a fresh claim) instead of failing forever on
 * a dead one.
 */
public class McpSessionRevokedException extends RuntimeException {

    public McpSessionRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
