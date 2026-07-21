package de.mhus.vance.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrainPodService} — the parts that are pure
 * functions (staleness predicate, resolveEndpoint heuristic). The
 * persistence wrappers go through Spring Data MongoDB and rely on
 * the existing Mongo-testcontainer harness; they're exercised by the
 * brain test suite indirectly via {@code ClusterService}.
 */
class BrainPodServiceTest {

    private BrainPodRepository repository;
    private BrainPodService service;

    @BeforeEach
    void setUp() {
        repository = mock(BrainPodRepository.class);
        service = new BrainPodService(repository);
    }

    // ─── staleness ──────────────────────────────────────────────────

    @Test
    void isStale_returnsTrue_whenLastBeatOlderThanWindow() {
        Instant now = Instant.parse("2026-05-07T20:00:00Z");
        BrainPodDocument doc = BrainPodDocument.builder()
                .lastHeartbeatAt(now.minus(Duration.ofMinutes(5)))
                .build();

        assertThat(service.isStale(doc, now, Duration.ofMinutes(2))).isTrue();
    }

    @Test
    void isStale_returnsFalse_whenWithinWindow() {
        Instant now = Instant.parse("2026-05-07T20:00:00Z");
        BrainPodDocument doc = BrainPodDocument.builder()
                .lastHeartbeatAt(now.minus(Duration.ofSeconds(30)))
                .build();

        assertThat(service.isStale(doc, now, Duration.ofMinutes(2))).isFalse();
    }

    @Test
    void isStale_returnsFalse_whenNoHeartbeatYet() {
        // A pod that just registered has no lastHeartbeatAt yet — must
        // not flap to STALE in the boot-grace period.
        Instant now = Instant.parse("2026-05-07T20:00:00Z");
        BrainPodDocument doc = BrainPodDocument.builder()
                .lastHeartbeatAt(null)
                .build();

        assertThat(service.isStale(doc, now, Duration.ofMinutes(2))).isFalse();
    }

    // ─── resolveEndpoint heuristic ──────────────────────────────────

    @Test
    void resolveEndpoint_passesThroughHostPortAsIs() {
        // Anything containing a colon is treated as a literal endpoint
        // and bypasses the registry lookup.
        assertThat(service.resolveEndpoint("any-cluster", "10.0.0.5:8080"))
                .contains("10.0.0.5:8080");
    }

    @Test
    void resolveEndpoint_looksUpByNodeName_whenNoColon() {
        when(repository.findByClusterIdAndNodeName("c1", "maya-prosser"))
                .thenReturn(Optional.of(BrainPodDocument.builder()
                        .nodeName("maya-prosser")
                        .endpoint("10.1.2.3:9000")
                        .build()));

        assertThat(service.resolveEndpoint("c1", "maya-prosser"))
                .contains("10.1.2.3:9000");
    }

    @Test
    void resolveEndpoint_unknownNodeName_returnsEmpty() {
        when(repository.findByClusterIdAndNodeName("c1", "ghost-pod"))
                .thenReturn(Optional.empty());

        assertThat(service.resolveEndpoint("c1", "ghost-pod")).isEmpty();
    }

    @Test
    void resolveEndpoint_blankInput_returnsEmpty() {
        assertThat(service.resolveEndpoint("c1", "")).isEmpty();
        assertThat(service.resolveEndpoint("c1", "   ")).isEmpty();
    }

    // ─── resolveLiveEndpoint — routing-grade liveness filter ─────────

    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    @Test
    void resolveLiveEndpoint_returnsEndpoint_forLiveRunningNode() {
        when(repository.findByClusterIdAndNodeName("c1", "maya-prosser"))
                .thenReturn(Optional.of(BrainPodDocument.builder()
                        .nodeName("maya-prosser")
                        .endpoint("10.1.2.3:9000")
                        .status(PodStatus.RUNNING)
                        .lastHeartbeatAt(Instant.now().minus(Duration.ofSeconds(10)))
                        .build()));

        assertThat(service.resolveLiveEndpoint("c1", "maya-prosser", STALE_AFTER))
                .contains("10.1.2.3:9000");
    }

    @Test
    void resolveLiveEndpoint_returnsEmpty_forStaleNode() {
        // Crashed pod: row lingers at RUNNING but heartbeat is old — plain
        // resolveEndpoint would still return its dead endpoint.
        when(repository.findByClusterIdAndNodeName("c1", "dead-node"))
                .thenReturn(Optional.of(BrainPodDocument.builder()
                        .nodeName("dead-node")
                        .endpoint("10.5.30.87:9991")
                        .status(PodStatus.RUNNING)
                        .lastHeartbeatAt(Instant.now().minus(Duration.ofMinutes(10)))
                        .build()));

        assertThat(service.resolveLiveEndpoint("c1", "dead-node", STALE_AFTER)).isEmpty();
    }

    @Test
    void resolveLiveEndpoint_returnsEmpty_forStoppedNode() {
        // Cleanly stopped pod keeps its endpoint but must not be routed to.
        when(repository.findByClusterIdAndNodeName("c1", "gone-node"))
                .thenReturn(Optional.of(BrainPodDocument.builder()
                        .nodeName("gone-node")
                        .endpoint("10.5.30.88:9991")
                        .status(PodStatus.STOPPED)
                        .lastHeartbeatAt(Instant.now())
                        .build()));

        assertThat(service.resolveLiveEndpoint("c1", "gone-node", STALE_AFTER)).isEmpty();
    }

    @Test
    void resolveLiveEndpoint_passesThroughHostPortAsIs() {
        // Raw endpoints can't be liveness-checked by node-name; trusted as-is.
        assertThat(service.resolveLiveEndpoint("c1", "10.0.0.5:8080", STALE_AFTER))
                .contains("10.0.0.5:8080");
    }

    @Test
    void resolveLiveEndpoint_unknownNodeName_returnsEmpty() {
        when(repository.findByClusterIdAndNodeName("c1", "ghost-pod"))
                .thenReturn(Optional.empty());

        assertThat(service.resolveLiveEndpoint("c1", "ghost-pod", STALE_AFTER)).isEmpty();
    }
}
