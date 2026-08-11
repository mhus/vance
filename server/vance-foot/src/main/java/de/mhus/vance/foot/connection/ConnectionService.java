package de.mhus.vance.foot.connection;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.ErrorData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PingData;
import de.mhus.vance.api.ws.PongData;
import de.mhus.vance.api.ws.ClientContext;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.WelcomeData;
import de.mhus.vance.foot.auth.FootAuthService;
import de.mhus.vance.foot.auth.TransportGuard;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.permission.PermissionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import de.mhus.vance.foot.ui.WindowTitleService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the WebSocket lifecycle to the Brain. Obtains a JWT via
 * {@link FootAuthService} (cached / refreshed / password), opens the
 * WebSocket, hands inbound envelopes to the {@link MessageDispatcher}.
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
    private final PermissionService permissions;
    private final FootAuthService auth;

    private final ObjectMapper json = JsonMapper.builder().build();
    /** Most recent JWT minted during {@link #connect()}; reused for REST GETs. */
    private volatile @Nullable AccessTokenResponse currentToken;
    /** Identity the Brain reported in the WELCOME frame of the live connection. */
    private volatile @Nullable WelcomeData lastWelcome;

    /**
     * How long {@code /connect} waits for a cancelled reconnect dial to
     * finish before giving up. Slightly over the 10s handshake timeout in
     * {@link #openConnection()}, so the in-flight attempt always resolves
     * one way or the other first.
     */
    private static final long DIAL_SLOT_WAIT_MS = 12_000;

    private static final long DIAL_SLOT_POLL_MS = 100;

    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicReference<@Nullable VanceWebSocketClient> clientRef = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledExecutorService> keepAliveRef = new AtomicReference<>();
    /** Keep-alive interval in ms — doubles as the quiet threshold for the
     *  liveness-aware ping (see {@link #sendKeepAlivePing}). */
    private volatile long keepAliveIntervalMs;
    /** Set while a {@code /disconnect} or shutdown is in effect so a resulting close does NOT auto-reconnect. */
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    /** Holds the single active reconnect campaign, {@code null} when none is running. */
    private final AtomicReference<@Nullable ScheduledExecutorService> reconnectRef = new AtomicReference<>();
    /**
     * Session bound at the moment of an unexpected drop, captured before teardown
     * so the reconnect can take it back over ({@code SESSION_RESUME} with
     * {@code takeover=true}). Consumed once by {@code WelcomeHandler} on the
     * post-reconnect welcome. {@code null} when nothing was bound.
     */
    private volatile @Nullable ReconnectTarget reconnectTarget;
    private final AtomicLong requestCounter = new AtomicLong();
    /**
     * Wall-clock of the last frame we successfully handed to the socket.
     * Drives the keep-alive skip — see {@link #sendKeepAlivePing()} for why
     * outbound (not inbound) is the relevant signal.
     */
    private final AtomicLong lastOutboundAtMs = new AtomicLong();

    /** The session (and its active process) to re-adopt after an auto-reconnect. */
    public record ReconnectTarget(String sessionId,
                                  @Nullable String projectId,
                                  @Nullable String activeProcess) {}

    public ConnectionService(FootConfig config,
                             MessageDispatcher dispatcher,
                             ChatTerminal terminal,
                             SessionService sessions,
                             WindowTitleService windowTitle,
                             PermissionService permissions,
                             FootAuthService auth) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.terminal = terminal;
        this.sessions = sessions;
        this.windowTitle = windowTitle;
        this.permissions = permissions;
        this.auth = auth;
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
        assertTransportAllowed();
        // A manual connect takes over from any auto-reconnect campaign and
        // clears the "user wants us offline" latch set by /disconnect.
        intentionalClose.set(false);
        stopReconnect();
        DialSlot slot = awaitDialSlot();
        if (slot == DialSlot.INTERRUPTED) {
            terminal.println(Verbosity.WARN, "Interrupted while waiting for the running attempt.");
            return;
        }
        if (slot == DialSlot.BUSY) {
            terminal.println(Verbosity.WARN, "Connection state is %s — /disconnect first.", state.get());
            return;
        }
        if (state.get() == State.OPEN) {
            // A reconnect campaign got there first, in the moment between
            // stopReconnect() and the CAS. That is what the user asked
            // for, so say so instead of complaining about the state.
            terminal.info("Already connected.");
            return;
        }
        try {
            openConnection();
            state.set(State.OPEN);
        } catch (Exception e) {
            state.set(State.DISCONNECTED);
            clientRef.set(null);
            throw e;
        }
    }

    /** Outcome of {@link #awaitDialSlot()} — the three cases the caller reports differently. */
    private enum DialSlot {
        /** The caller owns the dial, or the connection came up on its own (check {@link #state}). */
        CLAIMED,
        /** Something else still holds the state after the full wait. */
        BUSY,
        /** The waiting thread was interrupted. */
        INTERRUPTED
    }

    /**
     * Claims the right to dial, i.e. moves {@code DISCONNECTED →
     * CONNECTING}.
     *
     * <p>Bounded wait rather than a bare {@code compareAndSet}: a
     * reconnect campaign we just cancelled may still be inside
     * {@link #tryDial()}, holding {@code CONNECTING}. Failing outright
     * there told the user to "/disconnect first" for a connection they
     * were only trying to bring up faster.
     */
    private DialSlot awaitDialSlot() {
        long deadline = System.nanoTime() + DIAL_SLOT_WAIT_MS * 1_000_000L;
        boolean announced = false;
        while (true) {
            if (state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
                return DialSlot.CLAIMED;
            }
            if (state.get() == State.OPEN) {
                return DialSlot.CLAIMED;
            }
            if (System.nanoTime() >= deadline) {
                return DialSlot.BUSY;
            }
            if (!announced) {
                // This blocks the REPL for up to DIAL_SLOT_WAIT_MS. Say so
                // once, or /connect looks like it swallowed the command.
                terminal.info("An attempt is already running — waiting for it to finish…");
                announced = true;
            }
            try {
                Thread.sleep(DIAL_SLOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DialSlot.INTERRUPTED;
            }
        }
    }

    /**
     * Performs the actual dial: mints a token, builds the client and blocks
     * on the handshake. Assumes state is already {@code CONNECTING}. Throws on
     * any failure without touching state — the caller owns the state reset.
     */
    private void openConnection() throws Exception {
        AccessTokenResponse token = auth.acquireAccessToken();
        currentToken = token;
        terminal.verbose("Access token ready, expires at "
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
                .clientContextJson(buildClientContextJson())
                .build();

        VanceWebSocketClient client = new VanceWebSocketClient(wsConfig, new Listener());
        clientRef.set(client);
        client.connect().get(10, TimeUnit.SECONDS);
        terminal.info("Connected to " + wsUri);
    }

    /** Closes the active WebSocket if any. Idempotent. Suppresses auto-reconnect. */
    public void disconnect(String reason) {
        // Latch first so a close callback triggered by client.close() below
        // does not kick off an auto-reconnect campaign.
        intentionalClose.set(true);
        stopReconnect();
        VanceWebSocketClient client = clientRef.getAndSet(null);
        state.set(State.DISCONNECTED);
        stopKeepAlive();
        sessions.clear();
        currentToken = null;
        lastWelcome = null;
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

    /** Unix-millis expiry of the live access token, or {@code null} when not connected. */
    public @Nullable Long currentTokenExpiry() {
        AccessTokenResponse t = currentToken;
        return (t == null || t.getExpiresAtTimestamp() == 0L) ? null : t.getExpiresAtTimestamp();
    }

    /**
     * Identity the Brain announced in the WELCOME frame of the current
     * connection ({@code null} when disconnected). Captured by
     * {@code WelcomeHandler}; used by {@code /me} to show who we are actually
     * authenticated as — which can differ from the stored login config.
     */
    public @Nullable WelcomeData lastWelcome() {
        return lastWelcome;
    }

    /** Records the WELCOME identity for the live connection. Called by {@code WelcomeHandler}. */
    public void setLastWelcome(WelcomeData welcome) {
        this.lastWelcome = welcome;
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
        keepAliveIntervalMs = intervalSeconds * 1000L;
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

    /**
     * Starts a background reconnect campaign after an unexpected drop, unless
     * one is already running, the user asked to stay offline, or auto-reconnect
     * is disabled. Idempotent: the {@code compareAndSet} on {@link #reconnectRef}
     * guarantees a single campaign even if both the ping-timeout path and a
     * subsequent {@code onClose} race to schedule one.
     */
    private void scheduleReconnect() {
        if (intentionalClose.get()) {
            return;
        }
        FootConfig.Reconnect rc = config.getBrain().getReconnect();
        if (!rc.isEnabled()) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-foot-reconnect");
            t.setDaemon(true);
            return t;
        });
        if (!reconnectRef.compareAndSet(null, scheduler)) {
            scheduler.shutdownNow();
            return;
        }
        long initialMs = Math.max(0L, rc.getInitialDelay().toMillis());
        terminal.println(Verbosity.INFO,
                "Connection lost — reconnecting (first retry in %ds)…", initialMs / 1000);
        scheduler.schedule(() -> runReconnect(rc, 1, initialMs), initialMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels any running reconnect campaign. Safe to call multiple times. */
    private void stopReconnect() {
        ScheduledExecutorService previous = reconnectRef.getAndSet(null);
        if (previous != null) {
            previous.shutdownNow();
        }
    }

    /**
     * Remembers the currently bound session before an unexpected drop tears it
     * down, so the reconnect can re-adopt exactly it. No-op when nothing is
     * bound. Only records a fresh target — never clobbers an existing one with
     * {@code null} (the second teardown pass after {@code abort()} runs against
     * an already-cleared {@link SessionService}).
     */
    private void captureReconnectTarget() {
        SessionService.BoundSession bound = sessions.current();
        if (bound != null) {
            reconnectTarget = new ReconnectTarget(
                    bound.sessionId(), bound.projectId(), sessions.activeProcess());
        }
    }

    /**
     * Returns and clears the session to re-adopt after a reconnect. Called by
     * {@code WelcomeHandler} on the welcome frame; {@code null} means this was a
     * fresh connect (not a reconnect) and normal auto-bootstrap should run.
     */
    public @Nullable ReconnectTarget consumePendingReconnectResume() {
        ReconnectTarget t = reconnectTarget;
        reconnectTarget = null;
        return t;
    }

    /**
     * One reconnect tick: dials once, and on failure re-schedules itself with
     * geometric backoff capped at {@code maxDelay}. Stops the campaign on
     * success, on a user disconnect, once already open, or when the optional
     * attempt cap is reached.
     */
    private void runReconnect(FootConfig.Reconnect rc, int attempt, long delayMs) {
        if (intentionalClose.get() || state.get() == State.OPEN) {
            stopReconnect();
            return;
        }
        if (tryDial()) {
            terminal.info("Reconnected.");
            stopReconnect();
            return;
        }
        if (rc.getMaxAttempts() > 0 && attempt >= rc.getMaxAttempts()) {
            terminal.println(Verbosity.WARN,
                    "Reconnect gave up after %d attempts — use /connect to retry.", attempt);
            stopReconnect();
            return;
        }
        long nextMs = Math.min(
                (long) (delayMs * rc.getBackoffMultiplier()),
                Math.max(delayMs, rc.getMaxDelay().toMillis()));
        terminal.println(Verbosity.DEBUG,
                "Reconnect attempt %d failed — retrying in %ds", attempt, nextMs / 1000);
        ScheduledExecutorService scheduler = reconnectRef.get();
        if (scheduler != null && !intentionalClose.get()) {
            scheduler.schedule(() -> runReconnect(rc, attempt + 1, nextMs), nextMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A single non-throwing dial used by the reconnect campaign. Mirrors
     * {@link #connect()} but reports success as a boolean instead of an
     * exception, and never disturbs the {@code intentionalClose} latch.
     */
    private boolean tryDial() {
        if (!state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            // Already CONNECTING/OPEN — treat OPEN as success, anything else as
            // "try again next tick".
            return state.get() == State.OPEN;
        }
        try {
            openConnection();
            state.set(State.OPEN);
            return true;
        } catch (Exception e) {
            state.set(State.DISCONNECTED);
            clientRef.set(null);
            terminal.println(Verbosity.DEBUG, "reconnect dial failed: %s", describe(e));
            return false;
        }
    }

    /** Human-readable exception summary — {@code getMessage()} is {@code null} for e.g. {@link TimeoutException}. */
    private static String describe(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    private void sendKeepAlivePing() {
        if (!isOpen()) {
            return;
        }
        // Skip on recent OUTBOUND traffic, never on inbound. The brain's
        // session-bind lease (SessionDocument.lastActivityAt, default 2min)
        // is refreshed by frames arriving AT the brain — i.e. by what we
        // send. Downstream streaming (chat chunks, progress pings) proves
        // the socket is alive but does nothing for the lease, so gating on
        // inbound made foot go quiet for the whole of a long engine turn:
        // the stale-bind sweeper released the binding, the next frame we
        // sent failed the heartbeat check, and the brain closed the
        // connection underneath a still-running engine.
        long sinceOutboundMs = System.currentTimeMillis() - lastOutboundAtMs.get();
        if (keepAliveIntervalMs > 0 && sinceOutboundMs < keepAliveIntervalMs) {
            terminal.println(Verbosity.DEBUG,
                    "keepalive skipped — outbound %dms ago (lease fresh)", sinceOutboundMs);
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
            // Pong overdue. Inbound traffic in the meantime says the brain
            // is alive and simply queued our pong behind a busy stream —
            // that must not tear down a healthy connection (the ping still
            // did its real job: it refreshed the lease).
            long sinceInboundMs = System.currentTimeMillis() - dispatcher.lastInboundAtMs();
            if (keepAliveIntervalMs > 0 && sinceInboundMs < keepAliveIntervalMs) {
                terminal.println(Verbosity.DEBUG,
                        "ping unanswered (%s) but inbound %dms ago — keeping connection",
                        describe(e), sinceInboundMs);
                return;
            }
            // Nothing in either direction: the socket is wedged — typically a
            // half-open TCP left behind when a middlebox dropped the idle
            // connection without a FIN, so onClose never fired. Tear it down
            // and let the reconnect campaign take over instead of pinging a
            // corpse forever.
            terminal.println(Verbosity.WARN,
                    "ping failed: %s — connection looks dead, reconnecting", describe(e));
            handleUnexpectedDrop("ping timeout");
        }
    }

    /**
     * Idempotent teardown of a dead / half-open connection followed by a
     * reconnect campaign. Reached from the ping-timeout path (onClose never
     * fires on a half-open socket). Uses {@link VanceWebSocketClient#abort()}
     * rather than a clean close so we don't block on a close reply that will
     * never come.
     */
    private void handleUnexpectedDrop(String why) {
        captureReconnectTarget();
        VanceWebSocketClient dead = clientRef.getAndSet(null);
        state.set(State.DISCONNECTED);
        stopKeepAlive();
        sessions.clear();
        windowTitle.setConnection("disconnected");
        dispatcher.failAllPending(new IllegalStateException("Connection lost: " + why));
        if (dead != null) {
            try {
                dead.abort();
            } catch (Exception ignore) {
                // Best-effort — the socket is already gone.
            }
        }
        scheduleReconnect();
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
            lastOutboundAtMs.set(System.currentTimeMillis());
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
                    err.getErrorMessage() == null ? "(no message)" : err.getErrorMessage(),
                    err.getReason());
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
                    err.getErrorMessage() == null ? "(no message)" : err.getErrorMessage(),
                    err.getReason());
        }
        return json.convertValue(reply.getData(), replyType);
    }

    @PreDestroy
    void shutdown() {
        disconnect("shutdown");
    }

    /**
     * Rejects plaintext ({@code http://}/{@code ws://}) transport to a
     * non-loopback brain unless {@code vance.brain.allowInsecureTransport}
     * is set — otherwise the mint-token POST carries the password in
     * cleartext over the network (code-review Phase 2). Loopback plaintext
     * (local dev) is always allowed; a plaintext base with a configured
     * password is warned about either way.
     */
    void assertTransportAllowed() {
        boolean hasPassword = config.getAuth().getPassword() != null
                && !config.getAuth().getPassword().isBlank();
        TransportGuard.assertAllowed(
                config.getBrain().getHttpBase(),
                config.getBrain().getWsBase(),
                config.getBrain().isAllowInsecureTransport(),
                hasPassword,
                msg -> terminal.println(Verbosity.WARN, "%s", msg));
    }

    /**
     * Builds the JSON-encoded {@link ClientContext} sent on the handshake
     * so the brain can tell the LLM which platform / shell this client's
     * {@code client_exec_run} calls run on. Never throws — a serialization
     * failure just drops the header (the connection still opens; the brain
     * degrades to "no client context").
     */
    private @Nullable String buildClientContextJson() {
        try {
            ClientContext ctx = ClientContext.builder()
                    .os(osFamily())
                    .arch(System.getProperty("os.arch"))
                    // Mirror ClientExecutorService's own shell selection —
                    // this is the interpreter client_exec_run actually uses.
                    .shell(isWindows() ? "cmd.exe" : "/bin/sh")
                    .cwd(System.getProperty("user.dir"))
                    .sandboxEnabled(permissions.isSandboxEnabled())
                    .timezone(ZoneId.systemDefault().getId())
                    .build();
            return json.writeValueAsString(ctx);
        } catch (Exception e) {
            terminal.verbose("Client-context header skipped (ignored): " + e.getMessage());
            return null;
        }
    }

    /** Normalised OS family for {@link ClientContext#getOs()}. */
    private static String osFamily() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) return "linux";
        return "unknown";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
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
            // Capture the bound session before clearing so an ensuing reconnect
            // can take it back over. Only for drops we did not ask for.
            if (!intentionalClose.get()) {
                captureReconnectTarget();
            }
            clientRef.set(null);
            state.set(State.DISCONNECTED);
            stopKeepAlive();
            sessions.clear();
            windowTitle.setConnection("disconnected");
            dispatcher.failAllPending(new IllegalStateException(
                    "Connection closed (" + statusCode + ")"));
            terminal.info("WebSocket closed: " + statusCode
                    + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
            // A close we did not ask for (peer/idle/abnormal 1006) kicks off an
            // auto-reconnect; a user /disconnect latched intentionalClose and is
            // left alone.
            if (!intentionalClose.get()) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Throwable error) {
            terminal.error("WebSocket error: " + error.getMessage());
        }
    }
}
