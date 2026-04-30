package de.mhus.vance.api.ws.client;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDK-native WebSocket client speaking the Vance wire-protocol.
 *
 * Uses {@link java.net.http.WebSocket} under the hood and keeps {@code vance-api}
 * free of external WebSocket dependencies. Thread-safe for {@link #send},
 * {@link #close} and concurrent lifecycle callbacks.
 */
public class VanceWebSocketClient implements AutoCloseable {

    private final VanceWebSocketConfig config;
    private final VanceWebSocketClientListener listener;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile @Nullable WebSocket webSocket;

    public VanceWebSocketClient(VanceWebSocketConfig config, VanceWebSocketClientListener listener) {
        this(config, listener, defaultObjectMapper(), HttpClient.newHttpClient());
    }

    public VanceWebSocketClient(
            VanceWebSocketConfig config,
            VanceWebSocketClientListener listener,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.listener = listener;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Opens the WebSocket and runs the handshake. Completes normally once the
     * TCP/HTTP upgrade succeeded. If the server rejects the handshake (e.g. HTTP 401
     * for an invalid JWT), the returned future completes exceptionally.
     */
    public CompletableFuture<Void> connect() {
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(config.getConnectTimeout())
                .header(HandshakeHeaders.AUTHORIZATION,
                        HandshakeHeaders.BEARER_PREFIX + config.getJwtToken())
                .header(HandshakeHeaders.PROFILE, config.getProfile())
                .header(HandshakeHeaders.CLIENT_VERSION, config.getClientVersion());
        if (config.getClientName() != null && !config.getClientName().isBlank()) {
            builder.header(HandshakeHeaders.CLIENT_NAME, config.getClientName());
        }
        return builder.buildAsync(config.getUri(), new JdkListener())
                .thenAccept(ws -> this.webSocket = ws);
    }

    /** Sends a fully-built envelope. Fails the returned future on serialization or transport error. */
    public CompletableFuture<Void> send(WebSocketEnvelope envelope) {
        WebSocket ws = webSocket;
        if (ws == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("WebSocket is not connected"));
            return failed;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        return ws.sendText(json, true).thenApply(w -> null);
    }

    /**
     * Initiates a clean close. {@code statusCode} follows the standard WebSocket
     * close codes (1000 = normal closure).
     */
    public CompletableFuture<Void> close(int statusCode, String reason) {
        WebSocket ws = webSocket;
        if (ws == null || ws.isOutputClosed()) {
            return CompletableFuture.completedFuture(null);
        }
        return ws.sendClose(statusCode, reason).thenApply(w -> null);
    }

    @Override
    public void close() {
        close(WebSocket.NORMAL_CLOSURE, "").join();
    }

    /** Whether the client currently holds an open connection. */
    public boolean isOpen() {
        WebSocket ws = webSocket;
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    private static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private final class JdkListener implements WebSocket.Listener {

        private final StringBuilder fragmentBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
            listener.onOpen();
        }

        @Override
        public @Nullable CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            fragmentBuffer.append(data);
            if (last) {
                String full = fragmentBuffer.toString();
                fragmentBuffer.setLength(0);
                try {
                    WebSocketEnvelope envelope = objectMapper.readValue(full, WebSocketEnvelope.class);
                    listener.onMessage(envelope);
                } catch (Exception parseError) {
                    listener.onError(parseError);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            listener.onError(error);
        }
    }
}
