package de.mhus.vance.brain.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Server-initiated keep-alive + liveness detection for external WebSocket
 * connections ({@link LiveWebSocketHandler}).
 *
 * <p>Every {@code vance.ws.serverPingIntervalSeconds} the sweep sends a
 * WebSocket PING control frame to each registered session. Browsers (which
 * cannot send pings from JS) answer transparently; the pong lands in
 * {@link LiveWebSocketHandler#handlePongMessage} → {@link #recordPong}. Two
 * purposes:
 * <ul>
 *   <li><b>Keep-alive:</b> server→client traffic every interval stops an idle
 *       proxy/middlebox (e.g. Caddy's write/idle timeout) from tearing down a
 *       quiet connection.</li>
 *   <li><b>Eviction:</b> a connection that misses {@code serverPingMaxMissed}
 *       pongs is closed. The close cascades through
 *       {@link LiveWebSocketHandler#afterConnectionClosed} to release the
 *       session bind — so a reconnecting client resumes cleanly instead of
 *       hitting "session bound elsewhere" on a dead connection.</li>
 * </ul>
 *
 * <p><b>Never blocks the sweep.</b> The registered sessions are
 * {@code ConcurrentWebSocketSessionDecorator}s (set up in
 * {@link LiveWebSocketHandler}) whose send buffers and time-limits close a
 * stale client rather than hanging. Ping sends are additionally dispatched to a
 * small bounded pool, and staleness is decided purely on the last-pong
 * timestamp — so a wedged socket is evicted on the next tick even if its own
 * ping write is stuck.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketKeepAliveService {

    private final VanceBrainProperties properties;

    /** Registered external sessions (the decorators), keyed by {@code session.getId()}. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** Last time a pong (or the initial registration) was seen, epoch-millis, by session id. */
    private final Map<String, Long> lastSeenAtMs = new ConcurrentHashMap<>();

    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ExecutorService senders;

    @PostConstruct
    void start() {
        int interval = properties.getServerPingIntervalSeconds();
        if (interval <= 0) {
            log.info("Server-side WebSocket ping disabled (vance.ws.serverPingIntervalSeconds=0).");
            return;
        }
        senders = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "vance-ws-ping-send");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-ws-ping-sweep");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::sweepSafely, interval, interval, TimeUnit.SECONDS);
        scheduler = s;
        log.info("Server-side WebSocket ping every {}s (evict after {} missed).",
                interval, properties.getServerPingMaxMissed());
    }

    /** Registers a freshly opened external session. */
    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        lastSeenAtMs.put(session.getId(), System.currentTimeMillis());
    }

    /** Removes a session (on close/eviction). Safe to call for unknown ids. */
    public void unregister(String sessionId) {
        sessions.remove(sessionId);
        lastSeenAtMs.remove(sessionId);
    }

    /** Records a pong (or any inbound liveness signal) for a session. */
    public void recordPong(String sessionId) {
        lastSeenAtMs.computeIfPresent(sessionId, (id, prev) -> System.currentTimeMillis());
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // A scheduleWithFixedDelay task that throws is never rescheduled —
            // guard the whole sweep so one bad session cannot kill the loop.
            log.warn("WebSocket ping sweep failed (loop continues): {}", e.toString());
        }
    }

    /** Package-private for tests. One eviction/ping pass over all registered sessions. */
    void sweep() {
        int interval = properties.getServerPingIntervalSeconds();
        int maxMissed = Math.max(1, properties.getServerPingMaxMissed());
        long now = System.currentTimeMillis();
        long staleMs = (long) interval * maxMissed * 1000L;
        ExecutorService pool = senders;
        sessions.forEach((id, session) -> {
            long last = lastSeenAtMs.getOrDefault(id, now);
            if (now - last > staleMs) {
                log.debug("WebSocket {} missed {} pings (>{}ms silent) — evicting.",
                        id, maxMissed, staleMs);
                evict(id, session);
            } else if (pool != null) {
                pool.execute(() -> pingOne(id, session));
            }
        });
    }

    private void pingOne(String id, WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new PingMessage());
            } else {
                unregister(id);
            }
        } catch (Exception e) {
            // The decorator throwing (buffer/time limit) or a raw IO error both
            // mean the client is gone — drop it now rather than next tick.
            log.debug("Server ping to {} failed ({}) — evicting.", id, e.toString());
            evict(id, session);
        }
    }

    private void evict(String id, WebSocketSession session) {
        unregister(id);
        try {
            // Cascades to LiveWebSocketHandler.afterConnectionClosed → session
            // unbind, freeing the bind for a clean reconnect-resume.
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception e) {
            log.trace("Close of stale WebSocket {} failed (ignored): {}", id, e.toString());
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (senders != null) {
            senders.shutdownNow();
        }
    }
}
