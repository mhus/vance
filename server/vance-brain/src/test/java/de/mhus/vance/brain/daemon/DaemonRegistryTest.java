package de.mhus.vance.brain.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.daemon.DaemonRegistry.DaemonKey;
import de.mhus.vance.brain.daemon.DaemonRegistry.DaemonRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class DaemonRegistryTest {

    private DaemonRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DaemonRegistry();
        // Most existing tests assume immediate-drop on disconnect; opt in
        // to the stale-keep behaviour explicitly per test. Phase C tests
        // re-enable the TTL where needed.
        registry.staleTtlSeconds = 0;
    }

    @Test
    void register_storesAndLooksUpByKey() {
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        ToolSpec spec = ToolSpec.builder().name("client_exec_run").build();

        Optional<DaemonRef> ref = registry.register(key, ws, List.of(spec));

        assertThat(ref).isPresent();
        assertThat(ref.get().manifest()).containsKey("client_exec_run");
        assertThat(registry.find(key)).isPresent();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_collisionFirstWins() {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");

        Optional<DaemonRef> first = registry.register(key, ws1, List.of(
                ToolSpec.builder().name("first").build()));
        Optional<DaemonRef> second = registry.register(key, ws2, List.of(
                ToolSpec.builder().name("second").build()));

        assertThat(first).isPresent();
        assertThat(second).as("second registration is rejected").isEmpty();
        assertThat(registry.find(key).orElseThrow().wsSession()).isSameAs(ws1);
        assertThat(registry.find(key).orElseThrow().manifest()).containsKey("first");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_sameSessionUpdatesManifestInPlace() {
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");

        registry.register(key, ws, List.of(ToolSpec.builder().name("a").build()));
        Optional<DaemonRef> refreshed = registry.register(key, ws, List.of(
                ToolSpec.builder().name("a").build(),
                ToolSpec.builder().name("b").build()));

        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().manifest()).containsKeys("a", "b");
        // registeredAt stays the same — only lastSeenAt bumps.
        assertThat(refreshed.get().registeredAt())
                .isEqualTo(registry.find(key).orElseThrow().registeredAt());
    }

    @Test
    void unregister_removesByWebSocketSession() {
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, ws, List.of());

        registry.unregister(ws);

        assertThat(registry.find(key)).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregister_ignoresUnknownSession() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.unregister(ws);  // no throw, no log spam
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregister_doesNotRemoveReplacedEntry() {
        // Race: session A registers, gets replaced by session B (different
        // WS but same key — not currently allowed, but simulate via two
        // distinct keys for the same name in a different project). The
        // intent: unregister(wsA) must not touch wsB's entry.
        WebSocketSession wsA = mock(WebSocketSession.class);
        WebSocketSession wsB = mock(WebSocketSession.class);
        DaemonKey key1 = new DaemonKey("acme", "ops",  "x");
        DaemonKey key2 = new DaemonKey("acme", "dev",  "x");
        registry.register(key1, wsA, List.of());
        registry.register(key2, wsB, List.of());

        registry.unregister(wsA);

        assertThat(registry.find(key1)).isEmpty();
        assertThat(registry.find(key2)).isPresent();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void listInProject_filtersByTenantAndProject() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register(new DaemonKey("acme", "ops",  "a"), ws, List.of());
        registry.register(new DaemonKey("acme", "ops",  "b"), mock(WebSocketSession.class), List.of());
        registry.register(new DaemonKey("acme", "dev",  "c"), mock(WebSocketSession.class), List.of());
        registry.register(new DaemonKey("globex", "ops","d"), mock(WebSocketSession.class), List.of());

        assertThat(registry.listInProject("acme", "ops"))
                .extracting(r -> r.key().daemonName())
                .containsExactlyInAnyOrder("a", "b");
        assertThat(registry.listInTenant("acme"))
                .extracting(r -> r.key().daemonName())
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void touch_bumpsLastSeen() throws InterruptedException {
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "x");
        registry.register(key, ws, List.of());
        java.time.Instant t1 = registry.find(key).orElseThrow().lastSeenAt();
        Thread.sleep(5);
        registry.touch(ws);
        java.time.Instant t2 = registry.find(key).orElseThrow().lastSeenAt();
        assertThat(t2).isAfter(t1);
    }

    @Test
    void daemonKey_rejectsBlankParts() {
        assertThatThrownBy(() -> new DaemonKey("", "ops", "x"))
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new DaemonKey("acme", "", "x"))
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> new DaemonKey("acme", "ops", ""))
                .hasMessageContaining("daemonName");
    }

    // ─── Phase C — stale state on disconnect ────────────────────────────

    @Test
    void unregister_marksStaleWhenTtlPositive() {
        registry.staleTtlSeconds = 60;
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, ws, List.of());

        registry.unregister(ws);

        // Entry stays for the TTL window, marked stale.
        DaemonRef ref = registry.find(key).orElseThrow();
        assertThat(ref.stale()).isTrue();
        assertThat(ref.disconnectedAt()).isNotNull();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void reconnect_replacesStaleEntry() {
        registry.staleTtlSeconds = 60;
        WebSocketSession wsA = mock(WebSocketSession.class);
        WebSocketSession wsB = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, wsA, List.of(
                de.mhus.vance.api.tools.ToolSpec.builder().name("v1").build()));
        registry.unregister(wsA);
        // Stale entry sitting there.
        assertThat(registry.find(key).orElseThrow().stale()).isTrue();

        // New connection re-registers with same key but a different WS —
        // first-wins should NOT apply because the previous entry is stale.
        Optional<DaemonRef> replaced = registry.register(key, wsB, List.of(
                de.mhus.vance.api.tools.ToolSpec.builder().name("v2").build()));

        assertThat(replaced).isPresent();
        DaemonRef now = registry.find(key).orElseThrow();
        assertThat(now.stale()).isFalse();
        assertThat(now.wsSession()).isSameAs(wsB);
        assertThat(now.manifest()).containsOnlyKeys("v2");
    }

    @Test
    void liveCollision_stillFirstWins() {
        registry.staleTtlSeconds = 60;
        WebSocketSession wsA = mock(WebSocketSession.class);
        WebSocketSession wsB = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, wsA, List.of());

        // wsA is still LIVE — wsB's registration must be rejected.
        Optional<DaemonRef> denied = registry.register(key, wsB, List.of());

        assertThat(denied).isEmpty();
        assertThat(registry.find(key).orElseThrow().wsSession()).isSameAs(wsA);
        assertThat(registry.find(key).orElseThrow().stale()).isFalse();
    }

    @Test
    void sweepStaleEntries_dropsAfterTtl() throws InterruptedException {
        // 1-second TTL so the sweep can actually drop the entry without
        // a long-running test. Slightly fragile on a heavily-loaded CI,
        // but Phase C's TTL is small and the sweep is fixedDelay 15s so
        // we invoke it directly here.
        registry.staleTtlSeconds = 1;
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, ws, List.of());
        registry.unregister(ws);
        assertThat(registry.find(key).orElseThrow().stale()).isTrue();

        Thread.sleep(1_100);
        registry.sweepStaleEntries();

        assertThat(registry.find(key)).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void sweepStaleEntries_keepsStillFreshEntries() {
        registry.staleTtlSeconds = 60;
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "server-prod-01");
        registry.register(key, ws, List.of());
        registry.unregister(ws);

        registry.sweepStaleEntries();

        assertThat(registry.find(key)).isPresent();
        assertThat(registry.find(key).orElseThrow().stale()).isTrue();
    }

    @Test
    void sweepStaleEntries_firesOnStaleDropCallback() throws InterruptedException {
        registry.staleTtlSeconds = 1;
        java.util.concurrent.atomic.AtomicReference<DaemonKey> dropped =
                new java.util.concurrent.atomic.AtomicReference<>();
        registry.setOnStaleDrop(dropped::set);
        WebSocketSession ws = mock(WebSocketSession.class);
        DaemonKey key = new DaemonKey("acme", "ops", "x");
        registry.register(key, ws, List.of());
        registry.unregister(ws);

        Thread.sleep(1_100);
        registry.sweepStaleEntries();

        assertThat(dropped.get()).isEqualTo(key);
    }
}
