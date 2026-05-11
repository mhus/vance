package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Eddie's outbound Working-WS to a single worker session — the
 * server-initiated mirror of the client-side WS that Foot/Web/Mobile
 * use. Eddie binds with {@code profile=eddie} so the worker pod runs
 * the connection-profile filters from {@code engine-message-routing.md}
 * §4.1.1 (no client-side tools, side-channel frames decorated with
 * {@code forwardedBy}, etc.).
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #connect()} opens the WebSocket against
 *       {@code ws://<podAddress>/ws} with the {@code Authorization} +
 *       {@code X-Vance-Profile=eddie} headers, waits for the
 *       {@code welcome} frame, then sends {@code session-resume} for
 *       the worker session and waits for the matching reply.</li>
 *   <li>Every subsequent frame is dispatched to the
 *       {@link EddieFrameRouter} on the receive thread, paired with
 *       the owning {@link WorkerLinkSnapshot}.</li>
 *   <li>{@link #sendUserChatInput(String)} /
 *       {@link #sendCommand(String, Object)} push frames to the worker
 *       (synchronous send, no ack).</li>
 *   <li>{@link #close()} sends a normal close frame and tears down.</li>
 * </ol>
 *
 * <h2>What this class does NOT do</h2>
 *
 * <ul>
 *   <li>No connection pooling — that's {@link EddieWorkerConnectionPool}.</li>
 *   <li>No reconnect-with-backoff loop — the pool decides when to retry.</li>
 *   <li>No persistence — {@code lastSeen} on the snapshot is bumped via
 *       the frame-router callback, not here.</li>
 * </ul>
 */
@Slf4j
public class EddieWorkerConnection implements AutoCloseable {

    private static final String CLIENT_VERSION = "vance-eddie/1";
    private static final String CLIENT_NAME = "eddie";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BIND_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EddieFrameRouter router;
    private final WorkerLinkSnapshot link;
    private final String userJwt;

    private volatile @Nullable WebSocket socket;
    private final ConcurrentMap<String, CompletableFuture<WebSocketEnvelope>> pendingReplies =
            new ConcurrentHashMap<>();

    public EddieWorkerConnection(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            EddieFrameRouter router,
            WorkerLinkSnapshot link,
            String userJwt) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.router = router;
        this.link = link;
        this.userJwt = userJwt;
    }

    /** The link this connection serves — exposes the worker process id, last-seen, etc. */
    public WorkerLinkSnapshot link() {
        return link;
    }

    /**
     * Opens the WebSocket, waits for {@code welcome}, then binds to the
     * worker session with {@code session-resume}. Returns when the
     * server's bind reply arrives. Throws on connect / bind failure —
     * caller (the pool) decides retry / close.
     */
    public void connect() {
        URI uri = URI.create("ws://" + link.getWorkerPodAddress() + "/ws"
                + "?profile=" + Profiles.EDDIE
                + "&clientVersion=" + CLIENT_VERSION
                + "&name=" + CLIENT_NAME);
        ReceiveListener listener = new ReceiveListener();
        WebSocket ws;
        try {
            ws = httpClient.newWebSocketBuilder()
                    .header(HandshakeHeaders.AUTHORIZATION,
                            HandshakeHeaders.BEARER_PREFIX + userJwt)
                    .header(HandshakeHeaders.PROFILE, Profiles.EDDIE)
                    .header(HandshakeHeaders.CLIENT_VERSION, CLIENT_VERSION)
                    .header(HandshakeHeaders.CLIENT_NAME, CLIENT_NAME)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, listener)
                    .get(CONNECT_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Surface the root-cause message — without it the outer
            // "Cannot open Working-WS" wrapper hides the real reason
            // (401, connection refused, TLS, ...) from log.warn callers
            // that use only .toString().
            throw new EddieWorkerConnectException(
                    "Cannot open Working-WS to " + link.getWorkerPodAddress()
                            + " for worker=" + link.getWorkerProcessId()
                            + " — " + rootCauseMessage(e), e);
        }
        this.socket = ws;
        log.debug("Eddie working-ws opened to {} for worker={}",
                link.getWorkerPodAddress(), link.getWorkerProcessId());

        // Wait for welcome frame so we know the handshake completed.
        try {
            listener.welcomeFuture.get(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            close();
            throw new EddieWorkerConnectException(
                    "Welcome frame not received from " + link.getWorkerPodAddress(), e);
        }

        // Bind the WS to the worker session.
        SessionResumeRequest req = new SessionResumeRequest();
        req.setSessionId(link.getWorkerSessionId());
        WebSocketEnvelope reply;
        try {
            reply = sendRequest(MessageType.SESSION_RESUME, req, BIND_TIMEOUT);
        } catch (Exception e) {
            close();
            throw new EddieWorkerConnectException(
                    "session-resume failed for worker=" + link.getWorkerProcessId(), e);
        }
        if (MessageType.ERROR.equals(reply.getType())) {
            close();
            throw new EddieWorkerConnectException(
                    "Worker rejected session-resume: " + reply.getData());
        }
        link.setLastSeen(Instant.now());
    }

    /**
     * Sends a {@code chat-message-appended}-style {@code chat-input}
     * (Eddie speaking on behalf of her user). Fire-and-forget — no ack
     * is awaited; the worker reply arrives asynchronously through the
     * frame router.
     */
    public void sendUserChatInput(String content) {
        // The wire frame for client-side chat-input is process-steer with
        // role=USER. Reusing the existing protocol path keeps the worker
        // unchanged — Eddie is just another client.
        Map<String, Object> payload = Map.of(
                "thinkProcessId", link.getWorkerProcessId(),
                "content", content);
        sendNotification(MessageType.PROCESS_STEER, payload);
    }

    /**
     * Sends an {@code external-command} frame (stop / pause / resume,
     * see {@code engine-message-routing.md} §5).
     */
    public void sendCommand(String command, @Nullable Object payload) {
        sendNotification(command, payload);
    }

    private void sendNotification(String type, @Nullable Object data) {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException(
                    "Eddie working-ws not connected for worker=" + link.getWorkerProcessId());
        }
        WebSocketEnvelope env = WebSocketEnvelope.notification(type, data);
        try {
            String json = objectMapper.writeValueAsString(env);
            ws.sendText(json, true).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Send '" + type + "' failed to worker=" + link.getWorkerProcessId(), e);
        }
    }

    private WebSocketEnvelope sendRequest(String type, @Nullable Object data, Duration timeout)
            throws Exception {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException(
                    "Eddie working-ws not connected for worker=" + link.getWorkerProcessId());
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<WebSocketEnvelope> future = new CompletableFuture<>();
        pendingReplies.put(requestId, future);
        try {
            WebSocketEnvelope env = WebSocketEnvelope.request(requestId, type, data);
            String json = objectMapper.writeValueAsString(env);
            ws.sendText(json, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pendingReplies.remove(requestId);
        }
    }

    @Override
    public void close() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "eddie-close");
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        for (CompletableFuture<WebSocketEnvelope> f : pendingReplies.values()) {
            f.completeExceptionally(new IllegalStateException("Connection closed"));
        }
        pendingReplies.clear();
    }

    private final class ReceiveListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        final CompletableFuture<Void> welcomeFuture = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                try {
                    WebSocketEnvelope env = objectMapper.readValue(json, WebSocketEnvelope.class);
                    dispatch(env);
                } catch (RuntimeException e) {
                    log.warn("Eddie working-ws: malformed frame from {}: {}",
                            link.getWorkerPodAddress(), e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        private void dispatch(WebSocketEnvelope env) {
            link.setLastSeen(Instant.now());

            // Reply to a previous request?
            String replyTo = env.getReplyTo();
            if (replyTo != null) {
                CompletableFuture<WebSocketEnvelope> pending = pendingReplies.remove(replyTo);
                if (pending != null) {
                    pending.complete(env);
                    return;
                }
                log.debug("Eddie working-ws: unmatched reply id={} type={}",
                        replyTo, env.getType());
                return;
            }

            // Welcome handshake?
            if (MessageType.WELCOME.equals(env.getType())) {
                welcomeFuture.complete(null);
                return;
            }
            // Notification → frame router.
            router.onFrame(env, link);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            for (CompletableFuture<WebSocketEnvelope> f : pendingReplies.values()) {
                f.completeExceptionally(new IllegalStateException(
                        "Worker closed: " + statusCode + " " + reason));
            }
            pendingReplies.clear();
            log.debug("Eddie working-ws closed by peer {} (status={} reason={})",
                    link.getWorkerPodAddress(), statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            for (CompletableFuture<WebSocketEnvelope> f : pendingReplies.values()) {
                f.completeExceptionally(error);
            }
            pendingReplies.clear();
            log.warn("Eddie working-ws error to {}: {}",
                    link.getWorkerPodAddress(), error.toString());
        }
    }

    /**
     * Walks the {@link Throwable#getCause()} chain and returns the
     * deepest non-blank message — typical Java HttpClient WS failures
     * wrap {@link java.util.concurrent.ExecutionException} around the
     * actual {@link java.io.IOException} / {@code WebSocketHandshakeException},
     * and {@link Throwable#toString()} on the wrapper hides the
     * diagnostic.
     */
    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        String last = null;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) last = m;
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return last != null ? last : t.getClass().getSimpleName();
    }

    /** Connect / bind failure. The pool decides retry semantics. */
    public static class EddieWorkerConnectException extends RuntimeException {
        public EddieWorkerConnectException(String message) {
            super(message);
        }

        public EddieWorkerConnectException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
