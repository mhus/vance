package de.mhus.vance.brain.ws.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.ws.RemoteClientAnnounce;
import de.mhus.vance.api.ws.RemoteClientState;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roster behaviour of the remote-client registry, exercised pod-locally (Redis
 * mocked as disabled — the local map is then the whole truth, which is also the
 * default deployment).
 */
class RemoteClientRegistryTest {

    private VanceRedisMessagingService redis;
    private RemoteClientRegistry registry;

    @BeforeEach
    void setUp() {
        redis = mock(VanceRedisMessagingService.class);
        when(redis.isEnabled()).thenReturn(false);
        registry = new RemoteClientRegistry(redis, JsonMapper.builder().build());
    }

    private static WebSocketSession ws(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private static RemoteClientAnnounce announce(String clientId) {
        return RemoteClientAnnounce.builder()
                .clientId(clientId)
                .label("mba:~/work (pid 1)")
                .host("mba")
                .cwd("~/work")
                .pid(1)
                .version("dev")
                .lastSeq(0)
                .build();
    }

    @Test
    void announce_makesTheClientFindableAndOwned() {
        registry.announce(ws("c1"), "acme", "alice", announce("fc_1"));

        assertThat(registry.findLocal("fc_1")).isNotNull();
        assertThat(registry.owns("acme", "alice", "fc_1")).isTrue();
        assertThat(registry.listFor("acme", "alice"))
                .extracting(i -> i.getClientId()).containsExactly("fc_1");
    }

    @Test
    void owns_isFalseForAnotherUsersClient() {
        registry.announce(ws("c1"), "acme", "alice", announce("fc_1"));

        assertThat(registry.owns("acme", "bob", "fc_1"))
                .as("attaching to a foot is shell access — ownership is the whole rule")
                .isFalse();
    }

    @Test
    void owns_isFalseAcrossTenants() {
        registry.announce(ws("c1"), "acme", "alice", announce("fc_1"));

        assertThat(registry.owns("other", "alice", "fc_1")).isFalse();
    }

    @Test
    void listFor_hidesOtherUsersClients() {
        registry.announce(ws("c1"), "acme", "alice", announce("fc_alice"));
        registry.announce(ws("c2"), "acme", "bob", announce("fc_bob"));

        assertThat(registry.listFor("acme", "alice"))
                .extracting(i -> i.getClientId()).containsExactly("fc_alice");
    }

    @Test
    void reannounceOnNewSocket_rebindsAndForgetsTheOldOne() {
        WebSocketSession first = ws("c1");
        WebSocketSession second = ws("c2");
        registry.announce(first, "acme", "alice", announce("fc_1"));

        // The reconnect case: same process, same clientId, new transport —
        // possibly on a different pod entirely.
        registry.announce(second, "acme", "alice", announce("fc_1"));

        assertThat(registry.byWsSession(second)).isNotNull();
        assertThat(registry.byWsSession(first))
                .as("the stale socket must not still resolve to the client")
                .isNull();
    }

    @Test
    void forgetOldSocketAfterRebind_doesNotDropTheLiveClient() {
        WebSocketSession first = ws("c1");
        WebSocketSession second = ws("c2");
        registry.announce(first, "acme", "alice", announce("fc_1"));
        registry.announce(second, "acme", "alice", announce("fc_1"));

        // The old connection's close callback arrives after the reconnect —
        // a plain remove-by-id here would delete the live registration.
        registry.forget(first);

        assertThat(registry.findLocal("fc_1")).isNotNull();
    }

    @Test
    void forget_removesTheClient() {
        WebSocketSession session = ws("c1");
        registry.announce(session, "acme", "alice", announce("fc_1"));

        registry.forget(session);

        assertThat(registry.findLocal("fc_1")).isNull();
        assertThat(registry.listFor("acme", "alice")).isEmpty();
    }

    @Test
    void heartbeat_resolvesTheClientFromTheSocketNotThePayload() {
        WebSocketSession session = ws("c1");
        registry.announce(session, "acme", "alice", announce("fc_1"));

        // Payload claims somebody else's id — it must be ignored entirely,
        // otherwise any connection could rewrite a foreign roster row.
        RemoteClientRegistry.LocalClient updated = registry.heartbeat(session,
                RemoteClientState.builder()
                        .clientId("fc_somebody_else")
                        .sessionId("sess-9")
                        .uiMode("CHAT")
                        .busy(true)
                        .lastSeq(42)
                        .acceptingInput(true)
                        .build());

        assertThat(updated).isNotNull();
        assertThat(updated.clientId()).isEqualTo("fc_1");
        assertThat(registry.findLocal("fc_somebody_else")).isNull();
        assertThat(updated.info().getSessionId()).isEqualTo("sess-9");
        assertThat(updated.info().getLastSeq()).isEqualTo(42);
        assertThat(updated.info().isBusy()).isTrue();
    }

    @Test
    void heartbeat_fromAnUnannouncedConnectionIsIgnored() {
        assertThat(registry.heartbeat(ws("stranger"),
                RemoteClientState.builder().clientId("fc_1").build())).isNull();
    }

    @Test
    void isCrossPod_followsRedis() {
        assertThat(registry.isCrossPod()).isFalse();

        when(redis.isEnabled()).thenReturn(true);
        assertThat(registry.isCrossPod()).isTrue();
    }
}
