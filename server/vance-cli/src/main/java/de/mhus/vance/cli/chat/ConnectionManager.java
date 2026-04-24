package de.mhus.vance.cli.chat;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.client.VanceWebSocketClient;
import de.mhus.vance.api.ws.client.VanceWebSocketClientListener;
import de.mhus.vance.api.ws.client.VanceWebSocketConfig;
import de.mhus.vance.cli.BrainAccessClient;
import de.mhus.vance.cli.VanceCliConfig;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Owns the full WebSocket lifecycle for a chat session: mints a JWT, opens the
 * socket, routes incoming frames to a {@link Listener}, and closes on demand.
 *
 * <p>The manager is reusable — after a {@link #disconnect(String)} the caller
 * can {@link #connect()} again. Credentials come from the initial
 * {@link VanceCliConfig} but can be overridden per-connect via
 * {@link #connect(String, String, String)}.
 *
 * <p>The connect path runs on a single-threaded executor so the UI thread is
 * never blocked on the 10-second login/handshake timeout.
 */
public class ConnectionManager {

    public enum State { DISCONNECTED, CONNECTING, OPEN }

    public interface Listener {
        default void onStateChanged(State state) {}
        default void onInfo(String text) {}
        default void onSystem(String text) {}
        default void onError(String text) {}
        default void onReceived(WebSocketEnvelope envelope) {}
    }

    private final VanceCliConfig baseConfig;
    private final Listener listener;
    private final BrainAccessClient access = new BrainAccessClient();

    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicReference<@Nullable VanceWebSocketClient> clientRef = new AtomicReference<>();
    private final AtomicReference<Credentials> credentialsRef;

    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vance-cli-connect");
        t.setDaemon(true);
        return t;
    });

    public ConnectionManager(VanceCliConfig baseConfig, Listener listener) {
        this.baseConfig = baseConfig;
        this.listener = listener;
        this.credentialsRef = new AtomicReference<>(Credentials.from(baseConfig));
    }

    public State state() {
        return state.get();
    }

    public boolean isConnected() {
        VanceWebSocketClient c = clientRef.get();
        return c != null && c.isOpen();
    }

    public Credentials credentials() {
        return credentialsRef.get();
    }

    /** Kicks off a connect with the currently remembered credentials. */
    public void connect() {
        Credentials c = credentialsRef.get();
        doConnect(c);
    }

    /** Kicks off a connect with fresh credentials; remembers them for next time. */
    public void connect(String tenant, String username, String password) {
        Credentials c = new Credentials(tenant, username, password);
        credentialsRef.set(c);
        doConnect(c);
    }

    /** Best-effort close. No-op if already disconnected. */
    public void disconnect(String reason) {
        VanceWebSocketClient c = clientRef.getAndSet(null);
        if (c != null && c.isOpen()) {
            c.close(1000, reason);
        } else {
            // No active client — make sure state reflects reality.
            setState(State.DISCONNECTED);
        }
    }

    /**
     * Sends an envelope. Returns {@code false} if there is no open connection
     * so the caller can log a useful error instead of silently dropping.
     */
    public boolean send(WebSocketEnvelope envelope) {
        VanceWebSocketClient c = clientRef.get();
        if (c == null || !c.isOpen()) {
            return false;
        }
        c.send(envelope);
        return true;
    }

    public void shutdown() {
        disconnect("shutdown");
        connectExecutor.shutdownNow();
    }

    private void doConnect(Credentials creds) {
        if (!state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            listener.onError("Already connected or connecting — /disconnect first.");
            return;
        }
        notifyStateChanged();
        connectExecutor.submit(() -> doConnectBlocking(creds));
    }

    private void doConnectBlocking(Credentials creds) {
        AccessTokenResponse token;
        try {
            token = access.mint(
                    baseConfig.getBrain().getHttpBase(),
                    creds.tenant(),
                    creds.username(),
                    creds.password());
        } catch (Exception e) {
            listener.onError("Login failed: " + Errors.describe(e));
            setState(State.DISCONNECTED);
            return;
        }
        listener.onInfo("Minted JWT — expires "
                + Instant.ofEpochMilli(token.getExpiresAtTimestamp()));

        URI wsUri = URI.create(
                baseConfig.getBrain().getWsBase() + "/brain/" + creds.tenant() + "/ws");
        VanceWebSocketConfig wsConfig = VanceWebSocketConfig.builder()
                .uri(wsUri)
                .jwtToken(token.getToken())
                .clientType(ClientType.CLI)
                .clientVersion(baseConfig.getClient().getVersion())
                .build();

        // Self-reference so onClose can compareAndSet against the exact client it belongs to —
        // rapid disconnect/reconnect cycles must not let an old onClose null out the new client.
        AtomicReference<@Nullable VanceWebSocketClient> selfRef = new AtomicReference<>();
        VanceWebSocketClient client = new VanceWebSocketClient(wsConfig, new VanceWebSocketClientListener() {
            @Override
            public void onOpen() {
                setState(State.OPEN);
                listener.onSystem("WebSocket open — " + wsUri);
            }

            @Override
            public void onMessage(WebSocketEnvelope envelope) {
                listener.onReceived(envelope);
            }

            @Override
            public void onClose(int statusCode, @Nullable String reason) {
                clientRef.compareAndSet(selfRef.get(), null);
                setState(State.DISCONNECTED);
                listener.onSystem("WebSocket closed — " + statusCode
                        + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
            }

            @Override
            public void onError(Throwable error) {
                listener.onError("WebSocket error: " + Errors.describe(error));
            }
        });
        selfRef.set(client);
        clientRef.set(client);

        try {
            client.connect().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            clientRef.compareAndSet(client, null);
            setState(State.DISCONNECTED);
            listener.onError("Connect failed: " + Errors.describe(e));
        }
    }

    private void setState(State newState) {
        State previous = state.getAndSet(newState);
        if (previous != newState) {
            notifyStateChanged();
        }
    }

    private void notifyStateChanged() {
        listener.onStateChanged(state.get());
    }

    public record Credentials(String tenant, String username, String password) {
        static Credentials from(VanceCliConfig cfg) {
            return new Credentials(
                    cfg.getAuth().getTenant(),
                    cfg.getAuth().getUsername(),
                    cfg.getAuth().getPassword());
        }
    }
}
