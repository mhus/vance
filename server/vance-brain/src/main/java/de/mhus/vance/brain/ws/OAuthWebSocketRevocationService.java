package de.mhus.vance.brain.ws;

import de.mhus.vance.shared.oauthserver.OAuthGrantRevokedEvent;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Closes external WebSockets whose OAuth grant was revoked.
 *
 * <p>An HTTP request re-presents its token every time, so a revoked grant
 * stops it within the liveness window. A WebSocket presents its token once,
 * at the handshake, and then lives on — without this, disconnecting an app
 * would leave its open socket working for as long as the client keeps it up.
 *
 * <p>Two paths, because the revocation event is pod-local:
 * <ul>
 *   <li><b>Event</b> — {@link OAuthGrantRevokedEvent} on the pod that revoked
 *       closes that grant's connections here immediately.</li>
 *   <li><b>Sweep</b> — every {@code vance.oauth-server.ws-grant-sweep-interval}
 *       (default 60 s) each tracked connection's grant is re-checked through
 *       {@link OAuthServerGrantService#isActive}; a dead one is closed. This is
 *       what reaches connections on the other pods, bounded by the sweep
 *       interval plus the grant liveness cache.</li>
 * </ul>
 *
 * <p>Only connections opened with an OAuth credential are tracked —
 * registered by {@link VanceHandshakeInterceptor}. Entries leave on their own:
 * a connection whose socket has closed is dropped by the next sweep, and one
 * whose upgrade never completed (no socket attached) is dropped after
 * {@link #UNATTACHED_GRACE}.
 */
@Service
@Slf4j
public class OAuthWebSocketRevocationService {

    /** A handshake whose upgrade did not attach a socket within this window never will. */
    static final Duration UNATTACHED_GRACE = Duration.ofMinutes(2);

    static final CloseStatus GRANT_REVOKED = CloseStatus.POLICY_VIOLATION.withReason("OAuth grant revoked");

    private final OAuthServerGrantService grantService;
    private final Clock clock;

    /** editorId → tracked connection. */
    private final Map<String, Tracked> connections = new ConcurrentHashMap<>();

    private record Tracked(ConnectionContext connection, String grantId, Instant registeredAt) {}

    @Autowired
    public OAuthWebSocketRevocationService(OAuthServerGrantService grantService) {
        this(grantService, Clock.systemUTC());
    }

    /** Test seam: controllable clock. */
    OAuthWebSocketRevocationService(OAuthServerGrantService grantService, Clock clock) {
        this.grantService = grantService;
        this.clock = clock;
    }

    /** Track a connection opened with an OAuth credential; a no-op without a grant id. */
    public void track(ConnectionContext connection) {
        String grantId = connection.getOauthGrantId();
        if (grantId == null || grantId.isBlank()) {
            return;
        }
        connections.put(connection.getEditorId(), new Tracked(connection, grantId, clock.instant()));
    }

    /** Number of tracked connections — for tests and diagnostics. */
    int trackedCount() {
        return connections.size();
    }

    @EventListener
    public void onGrantRevoked(OAuthGrantRevokedEvent event) {
        connections.forEach((editorId, tracked) -> {
            if (tracked.grantId().equals(event.grantId())
                    && tracked.connection().getTenantId().equals(event.tenantId())) {
                log.debug(
                        "Closing WebSocket editor='{}' user='{}': grant '{}' revoked ({})",
                        editorId,
                        tracked.connection().getUserId(),
                        event.grantId(),
                        event.reason());
                close(editorId, tracked);
            }
        });
    }

    @Scheduled(
            fixedDelayString = "${vance.oauth-server.ws-grant-sweep-interval:PT60S}",
            initialDelayString = "${vance.oauth-server.ws-grant-sweep-interval:PT60S}")
    public void tick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("OAuth WebSocket grant sweep failed: {}", e.toString());
        }
    }

    /** One pass: drop finished entries, close connections whose grant is no longer live. */
    void sweep() {
        Instant now = clock.instant();
        connections.forEach((editorId, tracked) -> {
            ConnectionContext connection = tracked.connection();
            WebSocketSession socket = connection.getWebSocketSession();
            if (socket == null) {
                if (Duration.between(tracked.registeredAt(), now).compareTo(UNATTACHED_GRACE) > 0) {
                    connections.remove(editorId, tracked);
                }
                return;
            }
            if (!socket.isOpen()) {
                connections.remove(editorId, tracked);
                return;
            }
            boolean active;
            try {
                // The handshake admits only unpinned account credentials, so
                // the grant is checked without a project.
                active = grantService.isActive(
                        tracked.grantId(), connection.getTenantId(), connection.getUserId(), null);
            } catch (RuntimeException e) {
                // A lookup failure is not a revocation — keep the socket and
                // ask again next round rather than disconnecting everybody on
                // a database hiccup.
                log.debug("Grant check for WebSocket editor='{}' failed: {}", editorId, e.toString());
                return;
            }
            if (!active) {
                log.debug(
                        "Closing WebSocket editor='{}' user='{}': grant '{}' no longer live",
                        editorId,
                        connection.getUserId(),
                        tracked.grantId());
                close(editorId, tracked);
            }
        });
    }

    // The session belongs to the WebSocket container; it is closed explicitly
    // below, never by scope, so there is nothing for try-with-resources to own.
    @SuppressWarnings("PMD.CloseResource")
    private void close(String editorId, Tracked tracked) {
        WebSocketSession socket = tracked.connection().getWebSocketSession();
        if (socket == null) {
            // Revoked between handshake and upgrade: nothing to close yet.
            // The entry stays, so the sweep closes the socket once attached.
            return;
        }
        connections.remove(editorId, tracked);
        // Through the concurrency-safe decorator the live handler installed.
        WebSocketSession target = LiveWebSocketHandler.resolveSendTarget(socket);
        try {
            // Cascades to LiveWebSocketHandler.afterConnectionClosed — session
            // unbind and keep-alive unregister happen there.
            target.close(GRANT_REVOKED);
        } catch (Exception e) {
            log.trace("Close of WebSocket editor='{}' failed (ignored): {}", editorId, e.toString());
        }
    }
}
