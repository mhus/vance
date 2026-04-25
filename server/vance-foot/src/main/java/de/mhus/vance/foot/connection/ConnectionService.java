package de.mhus.vance.foot.connection;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.client.VanceWebSocketClient;
import de.mhus.vance.api.ws.client.VanceWebSocketClientListener;
import de.mhus.vance.api.ws.client.VanceWebSocketConfig;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the WebSocket lifecycle to the Brain. Mints a JWT against the HTTP
 * access endpoint, opens the WebSocket, hands inbound envelopes to the
 * {@link MessageDispatcher}.
 *
 * <p>One connection at a time. {@link #connect()} on an already-open
 * connection is a no-op with a verbose log; {@link #disconnect(String)} on a
 * closed connection is harmless.
 */
@Service
public class ConnectionService {

    public enum State { DISCONNECTED, CONNECTING, OPEN }

    private final FootConfig config;
    private final MessageDispatcher dispatcher;
    private final ChatTerminal terminal;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = JsonMapper.builder().build();

    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicReference<@Nullable VanceWebSocketClient> clientRef = new AtomicReference<>();

    public ConnectionService(FootConfig config, MessageDispatcher dispatcher, ChatTerminal terminal) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.terminal = terminal;
    }

    public State state() {
        return state.get();
    }

    public boolean isOpen() {
        VanceWebSocketClient c = clientRef.get();
        return c != null && c.isOpen();
    }

    /**
     * Opens a connection synchronously. Fails fast — the caller (typically the
     * REPL) shows the error to the user and continues without a connection.
     */
    public void connect() throws Exception {
        if (!state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            terminal.println(Verbosity.WARN, "Connection state is %s — /disconnect first.", state.get());
            return;
        }
        try {
            AccessTokenResponse token = mintToken();
            terminal.verbose("Minted JWT, expires at "
                    + java.time.Instant.ofEpochMilli(token.getExpiresAtTimestamp()));

            URI wsUri = URI.create(config.getBrain().getWsBase()
                    + "/brain/" + config.getAuth().getTenant() + "/ws");
            VanceWebSocketConfig wsConfig = VanceWebSocketConfig.builder()
                    .uri(wsUri)
                    .jwtToken(token.getToken())
                    .clientType(ClientType.CLI)
                    .clientVersion(config.getClient().getVersion())
                    .build();

            VanceWebSocketClient client = new VanceWebSocketClient(wsConfig, new Listener());
            clientRef.set(client);
            client.connect().get(10, TimeUnit.SECONDS);
            state.set(State.OPEN);
            terminal.info("Connected to " + wsUri);
        } catch (Exception e) {
            state.set(State.DISCONNECTED);
            clientRef.set(null);
            throw e;
        }
    }

    /** Closes the active WebSocket if any. Idempotent. */
    public void disconnect(String reason) {
        VanceWebSocketClient client = clientRef.getAndSet(null);
        state.set(State.DISCONNECTED);
        if (client != null && client.isOpen()) {
            client.close(1000, reason);
            terminal.info("Disconnected — " + reason);
        }
    }

    /**
     * Sends an envelope. Returns {@code true} on success; {@code false} if the
     * connection is not open so the caller can decide whether to surface that
     * to the user or queue.
     */
    public boolean send(WebSocketEnvelope envelope) {
        VanceWebSocketClient c = clientRef.get();
        if (c == null || !c.isOpen()) {
            return false;
        }
        c.send(envelope);
        return true;
    }

    @PreDestroy
    void shutdown() {
        disconnect("shutdown");
    }

    private AccessTokenResponse mintToken() throws Exception {
        String url = config.getBrain().getHttpBase()
                + "/brain/" + config.getAuth().getTenant()
                + "/access/" + config.getAuth().getUsername();
        String body = json.writeValueAsString(
                AccessTokenRequest.builder().password(config.getAuth().getPassword()).build());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token mint failed: HTTP " + response.statusCode()
                    + (response.body().isEmpty() ? "" : " — " + response.body()));
        }
        return json.readValue(response.body(), AccessTokenResponse.class);
    }

    private final class Listener implements VanceWebSocketClientListener {

        @Override
        public void onOpen() {
            state.set(State.OPEN);
        }

        @Override
        public void onMessage(WebSocketEnvelope envelope) {
            dispatcher.dispatch(envelope);
        }

        @Override
        public void onClose(int statusCode, @Nullable String reason) {
            clientRef.set(null);
            state.set(State.DISCONNECTED);
            terminal.info("WebSocket closed: " + statusCode
                    + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        }

        @Override
        public void onError(Throwable error) {
            terminal.error("WebSocket error: " + error.getMessage());
        }
    }
}
