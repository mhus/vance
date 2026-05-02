package de.mhus.vance.brain.enginemessage;

import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-target-pod WebSocket client for {@code /internal/engine-bind}.
 * One open connection per remote brain endpoint, kept around for the
 * lifetime of the local brain process; lazy-opened on first send to a
 * given endpoint.
 *
 * <p>{@link #send(String, EngineMessageDocument, Duration)} pushes one
 * frame and waits for the matching ack, with a timeout. Acks are
 * matched by {@code messageId} so multiple in-flight pushes on the same
 * connection don't confuse each other. On send failure or ack timeout
 * the caller can decide to retry — usually that's the role of the
 * outbox-replay loop, not the immediate caller.
 *
 * <p>Connection failure (close, IO error) drops the cached endpoint so
 * the next send transparently re-opens. No reconnect-with-backoff loop
 * here — outbox-replay handles "the remote is down" at a higher layer.
 *
 * <p>Auth: every connect carries the {@code X-Vance-Internal-Token}
 * header read from {@code vance.internal.token}.
 */
@Component
@Slf4j
public class EngineWsClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ConcurrentMap<String, EnginePeerConnection> peers = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final String internalToken;

    public EngineWsClient(
            ObjectMapper objectMapper,
            @Value("${vance.internal.token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * Pushes one EngineMessage frame to {@code endpoint} (a {@code host:port}
     * brain address) and waits up to {@code timeout} for the receiver's ack.
     *
     * @return the ack on success
     * @throws RuntimeException with cause on connect / send / ack failure
     */
    public EngineWsAck send(String endpoint, EngineMessageDocument message, Duration timeout) {
        EnginePeerConnection peer = peers.computeIfAbsent(endpoint, this::openConnection);
        CompletableFuture<EngineWsAck> ackFuture = peer.expectAck(message.getMessageId());
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (RuntimeException e) {
            peer.cancelAck(message.getMessageId());
            throw new EngineWsException("Failed to serialise EngineMessage " + message.getMessageId(), e);
        }
        try {
            peer.socket().sendText(json, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            peer.cancelAck(message.getMessageId());
            invalidate(endpoint);
            throw new EngineWsException("Send failed to " + endpoint + " messageId=" + message.getMessageId(), e);
        }
        try {
            EngineWsAck ack = ackFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!EngineWsAck.STATUS_ACK.equals(ack.status())) {
                throw new EngineWsException("Receiver rejected messageId=" + message.getMessageId()
                        + ": " + ack.reason());
            }
            return ack;
        } catch (TimeoutException e) {
            peer.cancelAck(message.getMessageId());
            throw new EngineWsException("Ack timeout for messageId=" + message.getMessageId(), e);
        } catch (Exception e) {
            peer.cancelAck(message.getMessageId());
            throw new EngineWsException("Ack wait failed for messageId=" + message.getMessageId(), e);
        }
    }

    /** Drops the cached connection for {@code endpoint} so the next send re-opens. */
    public void invalidate(String endpoint) {
        EnginePeerConnection prev = peers.remove(endpoint);
        if (prev != null) {
            prev.close();
        }
    }

    @PreDestroy
    void closeAll() {
        peers.values().forEach(EnginePeerConnection::close);
        peers.clear();
    }

    private EnginePeerConnection openConnection(String endpoint) {
        URI uri = URI.create("ws://" + endpoint + "/internal/engine-bind");
        AckRouter router = new AckRouter(objectMapper);
        WebSocket.Listener listener = router;
        WebSocket socket;
        try {
            socket = httpClient.newWebSocketBuilder()
                    .header(InternalAccessFilter.HEADER_INTERNAL_TOKEN, internalToken)
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, listener)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new EngineWsException("Cannot open engine-bind WS to " + endpoint, e);
        }
        log.debug("engine-bind WS opened to {}", endpoint);
        return new EnginePeerConnection(socket, router);
    }

    public static class EngineWsException extends RuntimeException {
        public EngineWsException(String message) {
            super(message);
        }

        public EngineWsException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** Bundle of one remote endpoint's socket and its ack-router. */
    private static final class EnginePeerConnection {
        private final WebSocket socket;
        private final AckRouter router;

        EnginePeerConnection(WebSocket socket, AckRouter router) {
            this.socket = socket;
            this.router = router;
        }

        WebSocket socket() {
            return socket;
        }

        CompletableFuture<EngineWsAck> expectAck(String messageId) {
            return router.expectAck(messageId);
        }

        void cancelAck(String messageId) {
            router.cancelAck(messageId);
        }

        void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "client-close");
            } catch (RuntimeException ignored) {
                // best-effort
            }
            router.failAllPending(new EngineWsException("connection closed"));
        }
    }

    /** Receives ack frames and completes pending {@link CompletableFuture}s by messageId. */
    private static final class AckRouter implements WebSocket.Listener {

        private final ObjectMapper objectMapper;
        private final ConcurrentMap<String, CompletableFuture<EngineWsAck>> pending = new ConcurrentHashMap<>();
        private final StringBuilder buffer = new StringBuilder();

        AckRouter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        CompletableFuture<EngineWsAck> expectAck(String messageId) {
            CompletableFuture<EngineWsAck> f = new CompletableFuture<>();
            pending.put(messageId, f);
            return f;
        }

        void cancelAck(String messageId) {
            CompletableFuture<EngineWsAck> f = pending.remove(messageId);
            if (f != null) {
                f.cancel(false);
            }
        }

        void failAllPending(Throwable cause) {
            for (Map.Entry<String, CompletableFuture<EngineWsAck>> e : pending.entrySet()) {
                e.getValue().completeExceptionally(cause);
            }
            pending.clear();
        }

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
                    EngineWsAck ack = objectMapper.readValue(json, EngineWsAck.class);
                    CompletableFuture<EngineWsAck> f = pending.remove(ack.messageId());
                    if (f != null) {
                        f.complete(ack);
                    } else {
                        // unmatched ack — sender already gave up and cancelled, or the
                        // peer sent a frame we never asked for; either way, log and drop.
                        log.debug("engine-bind WS: ack for unknown messageId={} status={}",
                                ack.messageId(), ack.status());
                    }
                } catch (RuntimeException e) {
                    log.warn("engine-bind WS: malformed ack frame: {}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failAllPending(new EngineWsException("peer closed: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failAllPending(new EngineWsException("transport error", error));
        }
    }
}
