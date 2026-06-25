package de.mhus.vance.brain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.events.SessionConnectionRegistry.RegisterOutcome;
import de.mhus.vance.brain.events.SessionConnectionRegistry.RegisterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * Verifies the multi-connection semantics of
 * {@link SessionConnectionRegistry} — same-user kick-old vs.
 * different-user reject/accept depending on
 * {@code allowMultipleClients}. See
 * {@code planning/multi-user-sessions.md} §2.1 / §1b.
 */
class SessionConnectionRegistryTest {

    private SessionConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionConnectionRegistry();
    }

    @Test
    void register_firstConnection_isAccepted() {
        WebSocketSession ws = mock(WebSocketSession.class);

        RegisterResult result = registry.register("s1", "alice", "ed-1", ws, false);

        assertThat(result.outcome()).isEqualTo(RegisterOutcome.ACCEPTED);
        assertThat(result.kicked()).isNull();
        assertThat(registry.find("s1")).contains(ws);
        assertThat(registry.connectionCount("s1")).isEqualTo(1);
    }

    @Test
    void register_sameUserReconnects_kicksOldAndAccepts() {
        WebSocketSession oldWs = mock(WebSocketSession.class);
        WebSocketSession newWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", oldWs, false);

        RegisterResult result = registry.register("s1", "alice", "ed-2", newWs, false);

        assertThat(result.outcome()).isEqualTo(RegisterOutcome.KICKED_OLD);
        assertThat(result.kicked()).isSameAs(oldWs);
        assertThat(registry.find("s1")).contains(newWs);
        assertThat(registry.connectionCount("s1")).isEqualTo(1);
    }

    @Test
    void register_differentUserOnPrivateSession_isRejected() {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, false);

        RegisterResult result = registry.register("s1", "bob", "ed-2", bobWs, false);

        assertThat(result.outcome()).isEqualTo(RegisterOutcome.REJECTED);
        assertThat(result.kicked()).isNull();
        assertThat(registry.connectionCount("s1")).isEqualTo(1);
        assertThat(registry.findAll("s1")).containsExactly(aliceWs);
    }

    @Test
    void register_differentUserOnSharedSession_isAccepted() {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, true);

        RegisterResult result = registry.register("s1", "bob", "ed-2", bobWs, true);

        assertThat(result.outcome()).isEqualTo(RegisterOutcome.ACCEPTED);
        assertThat(registry.connectionCount("s1")).isEqualTo(2);
        assertThat(registry.findAll("s1")).containsExactlyInAnyOrder(aliceWs, bobWs);
    }

    @Test
    void register_sameUserKickStillWorksOnSharedSession() {
        WebSocketSession aliceWs1 = mock(WebSocketSession.class);
        WebSocketSession aliceWs2 = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs1, true);
        registry.register("s1", "bob", "ed-2", bobWs, true);

        RegisterResult result = registry.register("s1", "alice", "ed-3", aliceWs2, true);

        assertThat(result.outcome()).isEqualTo(RegisterOutcome.KICKED_OLD);
        assertThat(result.kicked()).isSameAs(aliceWs1);
        assertThat(registry.findAll("s1")).containsExactlyInAnyOrder(aliceWs2, bobWs);
    }

    @Test
    void unregister_specificEditor_keepsOthers() {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, true);
        registry.register("s1", "bob", "ed-2", bobWs, true);

        registry.unregister("s1", "ed-1");

        assertThat(registry.findAll("s1")).containsExactly(bobWs);
        assertThat(registry.connectionCount("s1")).isEqualTo(1);
    }

    @Test
    void unregister_lastConnection_clearsSessionEntry() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", ws, false);

        registry.unregister("s1", "ed-1");

        assertThat(registry.find("s1")).isEmpty();
        assertThat(registry.connectionCount("s1")).isZero();
    }

    @Test
    void unregisterAll_dropsEveryConnection() {
        WebSocketSession aliceWs = mock(WebSocketSession.class);
        WebSocketSession bobWs = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", aliceWs, true);
        registry.register("s1", "bob", "ed-2", bobWs, true);

        registry.unregisterAll("s1");

        assertThat(registry.find("s1")).isEmpty();
        assertThat(registry.connectionCount("s1")).isZero();
    }

    @Test
    void find_nullOrUnknown_returnsEmpty() {
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.findAll(null)).isEmpty();
        assertThat(registry.findAll("missing")).isEmpty();
    }

    @Test
    void connectionCount_isolatedPerSession() {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        registry.register("s1", "alice", "ed-1", ws1, false);
        registry.register("s2", "alice", "ed-2", ws2, false);

        assertThat(registry.connectionCount("s1")).isEqualTo(1);
        assertThat(registry.connectionCount("s2")).isEqualTo(1);
        assertThat(registry.connectionCount("s3")).isZero();
    }
}
