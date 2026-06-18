package de.mhus.vance.api.ws.client;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Tail of the outbound send chain. JDK's {@link WebSocket#sendText}
     * is undefined when invoked while a previous send is still in
     * flight, so each new send is appended via {@code thenCompose}.
     * Failures of one send are isolated from the next so a transient
     * error doesn't permanently poison the chain.
     */
    private final AtomicReference<CompletableFuture<Void>> sendChain =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    /**
     * Tracks {@code envelope.id} → {@code envelope.type} for outgoing
     * requests so the inbound dispatch can auto-update
     * {@link #currentSessionId} when a {@code session-create} or
     * {@code session-resume} reply arrives.
     */
    private final Map<String, String> pendingTypes = new ConcurrentHashMap<>();

    /**
     * Bound session id, mirrored from the server side. Filled when a
     * {@code session-create} or {@code session-resume} reply arrives,
     * cleared on {@code session-unbind}. Travels in the outer
     * {@link LiveEnvelope} so the Face-Pod can route session-channel
     * frames to the project's home-pod (see planning/live-ws.md §7).
     */
    private volatile @Nullable String currentSessionId;

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
        // Remember the type so the inbound dispatch can update
        // currentSessionId when this particular request gets its reply.
        if (envelope.getId() != null) {
            pendingTypes.put(envelope.getId(), envelope.getType());
        }
        // session-unbind is fire-and-forget — clear the cached id right away
        // so subsequent envelopes drop back to the unbound state.
        if (MessageType.SESSION_UNBIND.equals(envelope.getType())) {
            currentSessionId = null;
        }
        String json;
        try {
            LiveEnvelope wrapped = new LiveEnvelope("session", currentSessionId, envelope);
            json = objectMapper.writeValueAsString(wrapped);
        } catch (JacksonException e) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        // Serialise outbound writes — JDK WebSocket forbids overlapping sendText.
        // Swallow the previous failure (per-send isolation) before chaining the next.
        return sendChain.updateAndGet(prev -> prev
                .exceptionally(e -> null)
                .thenCompose(ignored -> ws.sendText(json, true).thenApply(w -> null)));
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
                    LiveEnvelope outer = objectMapper.readValue(full, LiveEnvelope.class);
                    if (!"session".equals(outer.getChannel()) || outer.getPayload() == null) {
                        // Non-session channel frames are reserved for future
                        // use; ignore so a forward-compatible server can ship
                        // them without breaking older clients.
                        ws.request(1);
                        return null;
                    }
                    WebSocketEnvelope envelope =
                            objectMapper.convertValue(outer.getPayload(), WebSocketEnvelope.class);
                    if (envelope.getReplyTo() != null) {
                        String requestType = pendingTypes.remove(envelope.getReplyTo());
                        if (MessageType.SESSION_CREATE.equals(requestType)
                                || MessageType.SESSION_RESUME.equals(requestType)) {
                            extractSessionIdInto(envelope);
                        }
                    }
                    listener.onMessage(envelope);
                } catch (Exception parseError) {
                    listener.onError(parseError);
                }
            }
            ws.request(1);
            return null;
        }

        private void extractSessionIdInto(WebSocketEnvelope envelope) {
            Object data = envelope.getData();
            if (data == null) return;
            try {
                Map<?, ?> asMap = objectMapper.convertValue(data, Map.class);
                Object sid = asMap.get("sessionId");
                if (sid instanceof String s && !s.isBlank()) {
                    currentSessionId = s;
                }
            } catch (RuntimeException ignored) {
                // Reply data did not parse as a map — leave currentSessionId alone.
            }
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
