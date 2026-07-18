package de.mhus.vance.foot.connection;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.client.VanceWebSocketClient;
import de.mhus.vance.api.ws.client.VanceWebSocketClientListener;
import de.mhus.vance.api.ws.client.VanceWebSocketConfig;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import de.mhus.vance.foot.ui.WindowTitleService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
@lombok.extern.slf4j.Slf4j
public class ConnectionService {

    public enum State { DISCONNECTED, CONNECTING, OPEN }

    private final FootConfig config;
    private final MessageDispatcher dispatcher;
    private final ChatTerminal terminal;
    private final SessionService sessions;
    private final WindowTitleService windowTitle;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = JsonMapper.builder().build();
    /** Most recent JWT minted during {@link #connect()}; reused for REST GETs. */
    private volatile @Nullable AccessTokenResponse currentToken;

    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicReference<@Nullable VanceWebSocketClient> clientRef = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledExecutorService> keepAliveRef = new AtomicReference<>();
    private final AtomicLong requestCounter = new AtomicLong();

    public ConnectionService(FootConfig config,
                             MessageDispatcher dispatcher,
                             ChatTerminal terminal,
                             SessionService sessions,
                             WindowTitleService windowTitle) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.terminal = terminal;
        this.sessions = sessions;
        this.windowTitle = windowTitle;
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
            currentToken = token;
            terminal.verbose("Minted JWT, expires at "
                    + java.time.Instant.ofEpochMilli(token.getExpiresAtTimestamp()));

            URI wsUri = URI.create(config.getBrain().getWsBase()
                    + "/brain/" + config.getAuth().getTenant() + "/ws");
            String profile = config.getClient().getProfile();
            if (profile == null || profile.isBlank()) {
                profile = Profiles.FOOT;
            }
            String clientName = config.getClient().getName();
            if (clientName == null || clientName.isBlank()) {
                // Fallback so the brain always sees a non-empty
                // identifier; useful when multiple foot instances run
                // against the same tenant under different shell users
                // or hosts.
                clientName = config.getAuth().getUsername();
            }
            VanceWebSocketConfig wsConfig = VanceWebSocketConfig.builder()
                    .uri(wsUri)
                    .jwtToken(token.getToken())
                    .profile(profile)
                    .clientVersion(config.getClient().getVersion())
                    .clientName(clientName)
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
        stopKeepAlive();
        sessions.clear();
        currentToken = null;
        windowTitle.setConnection("disconnected");
        if (client != null && client.isOpen()) {
            client.close(1000, reason);
            terminal.info("Disconnected — " + reason);
        }
    }

    /**
     * The JWT minted at the last {@link #connect()}, or {@code null} if
     * we're not connected. Used by REST helpers (e.g. {@code BrainRestClientService})
     * that need to authenticate against the brain's HTTP endpoints with
     * the same credentials as the WebSocket.
     */
    public @Nullable String currentJwt() {
        AccessTokenResponse t = currentToken;
        return t == null ? null : t.getToken();
    }

    /**
     * Starts the keep-alive ping loop. Called by {@code WelcomeHandler} once
     * the Brain announces its expected interval. The scheduler is single-threaded
     * and daemon so it does not block JVM shutdown.
     *
     * <p>Each tick sends a {@code ping} via {@link #request} on the same
     * thread. That blocks the scheduler thread up to the request timeout —
     * fine, the next tick is gated on the previous returning anyway, and
     * a hung Brain is exactly the case where we want to know.
     */
    public void startKeepAlive(int intervalSeconds) {
        stopKeepAlive();
        if (intervalSeconds <= 0) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-foot-keepalive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sendKeepAlivePing,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        keepAliveRef.set(scheduler);
        terminal.println(Verbosity.DEBUG, "Keep-alive scheduled every %ds", intervalSeconds);
    }

    /** Stops the keep-alive loop. Safe to call multiple times. */
    public void stopKeepAlive() {
        ScheduledExecutorService previous = keepAliveRef.getAndSet(null);
        if (previous != null) {
            previous.shutdownNow();
        }
    }

    private void sendKeepAlivePing() {
        if (!isOpen()) {
            return;
        }
        long sent = System.currentTimeMillis();
        try {
            PongData pong = request(
                    MessageType.PING,
                    PingData.builder().clientTimestamp(sent).build(),
                    PongData.class,
                    Duration.ofSeconds(10));
            long rtt = System.currentTimeMillis() - sent;
            long oneWay = pong.getServerTimestamp() - pong.getClientTimestamp();
            terminal.println(Verbosity.DEBUG,
                    "ping rtt=%dms one-way=%dms", rtt, oneWay);
        } catch (Exception e) {
            terminal.println(Verbosity.WARN, "ping failed: %s", e.getMessage());
        }
    }

    /**
     * Sends an envelope and waits briefly for the underlying
     * {@link VanceWebSocketClient#send} future to complete so a
     * mid-send disconnect or serialisation error surfaces here as
     * {@code false} instead of silently swallowing the frame. Most
     * payloads finish in microseconds — the 2 s ceiling only kicks
     * in if the socket is wedged, which is itself a failure to report.
     */
    public boolean send(WebSocketEnvelope envelope) {
        VanceWebSocketClient c = clientRef.get();
        if (c == null || !c.isOpen()) {
            return false;
        }
        try {
            c.send(envelope).get(2, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("send timed out after 2s — frame likely lost");
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("send failed: {}", e.getCause() == null ? e : e.getCause().toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Sends a request and waits synchronously for the matching reply.
     * Strict timeout — fails with {@link TimeoutException} after
     * {@code timeout} elapses regardless of other connection activity.
     * Right default for short, bounded round-trips: PING, session-list,
     * tool-registration, kit-install, etc. — anything where "no reply
     * inside this window" really means "give up and report".
     *
     * <p>For chat-style requests that can legitimately take many
     * minutes while the brain streams unrelated progress frames, use
     * {@link #requestStreaming} instead — that one resets the deadline
     * on every inbound envelope and never throws.
     */
    public <T> T request(String type, @Nullable Object payload, Class<T> replyType, Duration timeout)
            throws BrainException, TimeoutException, InterruptedException {
        if (!isOpen()) {
            throw new IllegalStateException("Not connected — /connect first.");
        }
        String id = type + "_" + requestCounter.incrementAndGet();
        CompletableFuture<WebSocketEnvelope> future = new CompletableFuture<>();
        dispatcher.registerPendingReply(id, future);

        if (!send(WebSocketEnvelope.request(id, type, payload))) {
            dispatcher.cancelPendingReply(id);
            throw new IllegalStateException("Send failed — connection dropped between check and send.");
        }
        terminal.println(Verbosity.DEBUG, "→ %s (id=%s)", type, id);

        WebSocketEnvelope reply;
        try {
            reply = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            dispatcher.cancelPendingReply(id);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            // Pending future failed via failAllPending(...) on disconnect.
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause == null ? "Request failed" : cause.getMessage(), cause);
        }
        terminal.println(Verbosity.DEBUG, "← %s (replyTo=%s)", reply.getType(), reply.getReplyTo());

        if (MessageType.ERROR.equals(reply.getType())) {
            ErrorData err = json.convertValue(reply.getData(), ErrorData.class);
            throw new BrainException(err.getErrorCode(),
                    err.getErrorMessage() == null ? "(no message)" : err.getErrorMessage());
        }
        return json.convertValue(reply.getData(), replyType);
    }

    /**
     * Streaming variant of {@link #request} — never throws on timeout.
     * Used by the chat-steer path so a long-running engine turn
     * (Frankie, Marvin, Trillian, …) that keeps pushing progress
     * frames doesn't false-positive into a timeout abort.
     *
     * <p>{@code idleTimeout} counts time since the <em>last inbound
     * envelope on the connection</em> (any type — push frames like
     * {@code CHAT_MESSAGE_APPENDED} / {@code PROCESS_PROGRESS} reset
     * it). When the window elapses without any inbound traffic, the
     * method emits a "still waiting" notice on the terminal and keeps
     * waiting — it does NOT abort. A real connection drop still
     * surfaces as {@link IllegalStateException} via
     * {@code failAllPending}. Callers who need a hard cap should use
     * the strict {@link #request} overload above.
     */
    public <T> T requestStreaming(
            String type, @Nullable Object payload, Class<T> replyType, Duration idleTimeout)
            throws BrainException, InterruptedException {
        if (!isOpen()) {
            throw new IllegalStateException("Not connected — /connect first.");
        }
        String id = type + "_" + requestCounter.incrementAndGet();
        CompletableFuture<WebSocketEnvelope> future = new CompletableFuture<>();
        dispatcher.registerPendingReply(id, future);

        long sendAtMs = System.currentTimeMillis();
        if (!send(WebSocketEnvelope.request(id, type, payload))) {
            dispatcher.cancelPendingReply(id);
            throw new IllegalStateException("Send failed — connection dropped between check and send.");
        }
        terminal.println(Verbosity.DEBUG, "→ %s (id=%s, streaming)", type, id);

        long timeoutMs = idleTimeout.toMillis();
        // Floor for the moving deadline. Bumped to `now` when an idle
        // window elapses so the next "still waiting" notice fires
        // another `timeoutMs` later, not immediately on the next loop.
        long baselineMs = sendAtMs;
        WebSocketEnvelope reply = null;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                long lastActivity = Math.max(baselineMs, dispatcher.lastInboundAtMs());
                long deadline = lastActivity + timeoutMs;
                long waitMs = deadline - now;
                if (waitMs <= 0) {
                    long idleSec = Math.max(timeoutMs / 1000L,
                            (now - lastActivity) / 1000L);
                    terminal.println(Verbosity.INFO,
                            "… still waiting for brain (no activity for %ds, request id=%s)",
                            idleSec, id);
                    baselineMs = now;
                    continue;
                }
                long sliceMs = Math.min(waitMs, 2_000L);
                try {
                    reply = future.get(sliceMs, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException slice) {
                    // Not a real timeout — loop and re-evaluate the deadline.
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            dispatcher.cancelPendingReply(id);
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause == null ? "Request failed" : cause.getMessage(), cause);
        }
        terminal.println(Verbosity.DEBUG, "← %s (replyTo=%s)", reply.getType(), reply.getReplyTo());

        if (MessageType.ERROR.equals(reply.getType())) {
            ErrorData err = json.convertValue(reply.getData(), ErrorData.class);
            throw new BrainException(err.getErrorCode(),
                    err.getErrorMessage() == null ? "(no message)" : err.getErrorMessage());
        }
        return json.convertValue(reply.getData(), replyType);
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
            stopKeepAlive();
            sessions.clear();
            windowTitle.setConnection("disconnected");
            dispatcher.failAllPending(new IllegalStateException(
                    "Connection closed (" + statusCode + ")"));
            terminal.info("WebSocket closed: " + statusCode
                    + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        }

        @Override
        public void onError(Throwable error) {
            terminal.error("WebSocket error: " + error.getMessage());
        }
    }
}
