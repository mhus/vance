package de.mhus.vance.brain.ws.live;

import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * One pod-to-pod chat-streaming WebSocket from a Face-Pod to a remote
 * Home-Pod's {@code /internal/{tenant}/ws/chat} endpoint.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #open(Duration)} performs the upgrade handshake and waits
 *       until the socket is connected — the caller (usually the
 *       {@link LiveChatTunnelRegistry}) blocks on this so the first
 *       outbound frame doesn't race the handshake.</li>
 *   <li>{@link #send(WebSocketEnvelope)} forwards a frame to the Home-Pod.
 *       Serialised through the underlying socket's per-connection write
 *       path which Java's HttpClient guarantees ordering on.</li>
 *   <li>Inbound frames are accumulated across partial {@code onText} calls
 *       and, once complete, decoded as {@link WebSocketEnvelope} and handed
 *       to {@code incomingFrameSink} (typically writes a {@code LiveEnvelope}
 *       back to the external user's WS).</li>
 *   <li>{@link #close()} requests a normal closure.</li>
 * </ol>
 *
 * <p>No reconnect logic in v1 — if the tunnel breaks, the closeListener is
 * invoked and the registry tears the external WS state down. A reconnect
 * loop could be layered on top later.
 */
@Slf4j
public class LiveChatTunnel implements AutoCloseable {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final URI uri;
    private final Map<String, String> handshakeHeaders;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Consumer<WebSocketEnvelope> incomingFrameSink;
    private final Runnable closeListener;

    private volatile @Nullable WebSocket socket;

    public LiveChatTunnel(
            URI uri,
            Map<String, String> handshakeHeaders,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Consumer<WebSocketEnvelope> incomingFrameSink,
            Runnable closeListener) {
        this.uri = uri;
        this.handshakeHeaders = Map.copyOf(handshakeHeaders);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.incomingFrameSink = incomingFrameSink;
        this.closeListener = closeListener;
    }

    public void open(Duration timeout) throws Exception {
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(timeout);
        for (Map.Entry<String, String> e : handshakeHeaders.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        WebSocket ws = builder
                .buildAsync(uri, new Listener())
                .get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        this.socket = ws;
        log.debug("LiveChatTunnel opened to {}", uri);
    }

    public void send(WebSocketEnvelope envelope) throws Exception {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException("Tunnel not connected: " + uri);
        }
        String json = objectMapper.writeValueAsString(envelope);
        ws.sendText(json, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isOpen() {
        WebSocket ws = socket;
        return ws != null && !ws.isOutputClosed();
    }

    @Override
    public void close() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "tunnel-close");
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

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
                    WebSocketEnvelope env =
                            objectMapper.readValue(json, WebSocketEnvelope.class);
                    incomingFrameSink.accept(env);
                } catch (RuntimeException e) {
                    log.warn("LiveChatTunnel: malformed inbound frame from {}: {}",
                            uri, e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            log.debug("LiveChatTunnel closed by peer {} (status={} reason={})",
                    uri, statusCode, reason);
            try {
                closeListener.run();
            } catch (RuntimeException e) {
                log.warn("close-listener failed: {}", e.toString());
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            log.warn("LiveChatTunnel error to {}: {}", uri, error.toString());
            try {
                closeListener.run();
            } catch (RuntimeException e) {
                log.warn("close-listener failed after error: {}", e.toString());
            }
        }
    }
}
