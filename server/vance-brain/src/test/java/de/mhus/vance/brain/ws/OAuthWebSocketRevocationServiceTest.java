package de.mhus.vance.brain.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.oauthserver.OAuthGrantRevokedEvent;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * A WebSocket presents its token once; these are the two paths by which a
 * later grant revocation still reaches it — the pod-local event and the
 * periodic re-check that covers revocations on other pods.
 */
class OAuthWebSocketRevocationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private OAuthServerGrantService grantService;
    private MutableClock clock;
    private OAuthWebSocketRevocationService service;

    @BeforeEach
    void setUp() {
        grantService = mock(OAuthServerGrantService.class);
        clock = new MutableClock(T0);
        service = new OAuthWebSocketRevocationService(grantService, clock);
    }

    private static ConnectionContext connection(String editorId, String grantId) {
        ConnectionContext ctx =
                new ConnectionContext("acme", "alice", "alice", "web", "1.0", null, editorId, "10.0.0.1");
        ctx.setOauthGrantId(grantId);
        return ctx;
    }

    private static WebSocketSession openSocket(ConnectionContext ctx) {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.isOpen()).thenReturn(true);
        ctx.attach(socket);
        return socket;
    }

    @Test
    void track_ignoresConnectionsWithoutAGrant() {
        ConnectionContext ctx = new ConnectionContext("acme", "alice", "alice", "web", "1.0", null, "e1", "ip");

        service.track(ctx);

        assertThat(service.trackedCount()).isZero();
    }

    @Test
    void revokedEvent_closesTheConnectionsOfThatGrant_only() throws Exception {
        ConnectionContext revoked = connection("e1", "grant-7");
        ConnectionContext other = connection("e2", "grant-8");
        WebSocketSession revokedSocket = openSocket(revoked);
        WebSocketSession otherSocket = openSocket(other);
        service.track(revoked);
        service.track(other);

        service.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user"));

        verify(revokedSocket).close(OAuthWebSocketRevocationService.GRANT_REVOKED);
        verify(otherSocket, never()).close(any());
        assertThat(service.trackedCount()).isEqualTo(1);
    }

    @Test
    void revokedEvent_ofAnotherTenant_closesNothing() throws Exception {
        ConnectionContext ctx = connection("e1", "grant-7");
        WebSocketSession socket = openSocket(ctx);
        service.track(ctx);

        service.onGrantRevoked(new OAuthGrantRevokedEvent("other", "grant-7", "alice", "user"));

        verify(socket, never()).close(any());
    }

    /** The close goes through the live handler's concurrency-safe decorator when there is one. */
    @Test
    void close_usesTheSendTargetDecorator() throws Exception {
        ConnectionContext ctx = connection("e1", "grant-7");
        WebSocketSession raw = openSocket(ctx);
        WebSocketSession decorator = mock(WebSocketSession.class);
        raw.getAttributes().put(LiveWebSocketHandler.ATTR_SEND_TARGET, decorator);
        service.track(ctx);

        service.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user"));

        verify(decorator).close(OAuthWebSocketRevocationService.GRANT_REVOKED);
        verify(raw, never()).close(any());
    }

    /** Revocation on another pod: no event here, the sweep finds the dead grant. */
    @Test
    void sweep_closesConnectionsWhoseGrantIsNoLongerLive() throws Exception {
        ConnectionContext live = connection("e1", "grant-live");
        ConnectionContext dead = connection("e2", "grant-dead");
        WebSocketSession liveSocket = openSocket(live);
        WebSocketSession deadSocket = openSocket(dead);
        service.track(live);
        service.track(dead);
        when(grantService.isActive("grant-live", "acme", "alice", null)).thenReturn(true);
        when(grantService.isActive("grant-dead", "acme", "alice", null)).thenReturn(false);

        service.sweep();

        verify(deadSocket).close(OAuthWebSocketRevocationService.GRANT_REVOKED);
        verify(liveSocket, never()).close(any());
        assertThat(service.trackedCount()).isEqualTo(1);
    }

    /** A lookup failure is not a revocation — nobody is disconnected over a database hiccup. */
    @Test
    void sweep_keepsTheConnection_whenTheGrantLookupFails() throws Exception {
        ConnectionContext ctx = connection("e1", "grant-7");
        WebSocketSession socket = openSocket(ctx);
        service.track(ctx);
        when(grantService.isActive(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        service.sweep();

        verify(socket, never()).close(any());
        assertThat(service.trackedCount()).isEqualTo(1);
    }

    @Test
    void sweep_dropsClosedSockets_withoutAGrantLookup() {
        ConnectionContext ctx = connection("e1", "grant-7");
        WebSocketSession socket = openSocket(ctx);
        when(socket.isOpen()).thenReturn(false);
        service.track(ctx);

        service.sweep();

        assertThat(service.trackedCount()).isZero();
        verify(grantService, never()).isActive(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sweep_dropsHandshakesThatNeverAttachedASocket_afterTheGrace() {
        service.track(connection("e1", "grant-7"));

        service.sweep();
        assertThat(service.trackedCount()).isEqualTo(1);

        clock.advance(OAuthWebSocketRevocationService.UNATTACHED_GRACE.plusSeconds(1));
        service.sweep();
        assertThat(service.trackedCount()).isZero();
    }

    /**
     * Revoked between handshake and upgrade: nothing to close yet, so the
     * entry stays and the sweep closes the socket once it is attached.
     */
    @Test
    void revokedBeforeTheSocketAttached_isClosedByTheNextSweep() throws Exception {
        ConnectionContext ctx = connection("e1", "grant-7");
        service.track(ctx);

        service.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user"));
        assertThat(service.trackedCount()).isEqualTo(1);

        WebSocketSession socket = openSocket(ctx);
        when(grantService.isActive("grant-7", "acme", "alice", null)).thenReturn(false);
        service.sweep();

        verify(socket).close(OAuthWebSocketRevocationService.GRANT_REVOKED);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
