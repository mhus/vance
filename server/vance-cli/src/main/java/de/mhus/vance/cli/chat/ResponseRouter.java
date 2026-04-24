package de.mhus.vance.cli.chat;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Routes server replies to the command that sent the matching request.
 *
 * <p>When a command sends a request it registers a handler against its
 * request id via {@link #expectReply(String, Consumer, long)}. When a reply
 * envelope arrives whose {@code replyTo} matches, {@link #tryDispatch} removes
 * the entry and calls the handler. Entries older than their timeout are
 * evicted by {@link #evictExpired} and reported via the timeout sink so the
 * user sees {@code "request timed out"} rather than a silent hang.
 *
 * <p>Thread-safety: concurrent {@code expectReply}, {@code tryDispatch} and
 * {@code evictExpired} are safe via {@link ConcurrentHashMap}.
 */
public class ResponseRouter {

    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Consumer<String> timeoutSink;

    /**
     * @param timeoutSink called with the request-id of each evicted entry so the
     *                    UI can log a timeout message.
     */
    public ResponseRouter(Consumer<String> timeoutSink) {
        this.timeoutSink = timeoutSink;
    }

    /** Register a handler for the given request id with the default timeout. */
    public void expectReply(String requestId, Consumer<WebSocketEnvelope> handler) {
        expectReply(requestId, handler, DEFAULT_TIMEOUT_MS);
    }

    /** Register a handler for the given request id, expiring after {@code timeoutMs}. */
    public void expectReply(String requestId, Consumer<WebSocketEnvelope> handler, long timeoutMs) {
        long expiresAt = System.currentTimeMillis() + Math.max(1, timeoutMs);
        pending.put(requestId, new Pending(handler, expiresAt));
    }

    /**
     * Attempts to dispatch {@code envelope} to a registered handler.
     *
     * @return true if a handler matched and was invoked; false otherwise (the
     *         caller should fall back to its default handling, e.g. log the
     *         envelope verbatim).
     */
    public boolean tryDispatch(WebSocketEnvelope envelope) {
        String replyTo = envelope.getReplyTo();
        if (replyTo == null) {
            return false;
        }
        Pending entry = pending.remove(replyTo);
        if (entry == null) {
            return false;
        }
        try {
            entry.handler.accept(envelope);
        } catch (RuntimeException e) {
            timeoutSink.accept(replyTo + " — handler failed: " + Errors.describe(e));
        }
        return true;
    }

    /** Removes and reports entries whose timeout has passed. Safe to call from any thread. */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        pending.entrySet().removeIf(e -> {
            if (e.getValue().expiresAt < now) {
                expired.add(e.getKey());
                return true;
            }
            return false;
        });
        for (String id : expired) {
            timeoutSink.accept(id);
        }
    }

    /** Drop every pending entry silently — use on disconnect so stale requests do not fire timeouts. */
    public void clear() {
        pending.clear();
    }

    private record Pending(Consumer<WebSocketEnvelope> handler, long expiresAt) {}
}
