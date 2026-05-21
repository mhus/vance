package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import de.mhus.vance.shared.cluster.ClusterMasterStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic tests for the Cluster-Master election algorithm — see
 * {@code specification/cluster-project-management.md} §4.2. The tick is
 * called directly with a controlled {@code now} so all CAS branches are
 * reachable without scheduler timing.
 */
class ClusterMasterServiceTest {

    private static final String CLUSTER_ID = "default";
    private static final String SELF_POD = "self-pod-id";
    private static final String SELF_NODE = "self-node";
    private static final String SELF_ENDPOINT = "127.0.0.1:8080";
    private static final String OTHER_POD = "other-pod-id";

    private ClusterMasterStore store;
    private ClusterService clusterService;
    private ClusterProperties properties;
    private ClusterMasterService service;

    @BeforeEach
    void setUp() {
        store = mock(ClusterMasterStore.class);
        clusterService = mock(ClusterService.class);
        properties = new ClusterProperties();
        // Tight timings keep tests deterministic.
        properties.getMaster().setLeaseDuration(Duration.ofMinutes(5));
        properties.getMaster().setRenewSafetyMargin(Duration.ofMinutes(2));

        lenient().when(clusterService.selfClusterId()).thenReturn(CLUSTER_ID);
        lenient().when(clusterService.selfPodId()).thenReturn(SELF_POD);
        lenient().when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        BrainPodDocument selfPod = BrainPodDocument.builder()
                .podId(SELF_POD).nodeName(SELF_NODE).endpoint(SELF_ENDPOINT).build();
        lenient().when(clusterService.selfPod()).thenReturn(Optional.of(selfPod));

        service = new ClusterMasterService(store, clusterService, properties);
    }

    @Test
    void tick_firstElection_acquires() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        when(store.find(CLUSTER_ID)).thenReturn(Optional.empty());
        ClusterMasterDocument doc = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(SELF_POD).currentNodeName(SELF_NODE)
                .currentEndpoint(SELF_ENDPOINT).leaseUntil(now.plus(Duration.ofMinutes(5)))
                .build();
        when(store.tryAcquire(eq(CLUSTER_ID), eq(null), eq(SELF_POD), eq(SELF_NODE),
                eq(SELF_ENDPOINT), eq(now), any(Instant.class)))
                .thenReturn(Optional.of(doc));

        boolean result = service.tick(now);

        assertThat(result).isTrue();
        assertThat(service.isLocalPodMaster()).isTrue();
        verify(store, never()).renew(anyString(), anyString(), any());
    }

    @Test
    void tick_iAmMaster_leaseFar_doesNotRenew() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        // Lease is well within renewSafetyMargin (>2 min) — no renew needed.
        ClusterMasterDocument doc = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(SELF_POD).currentNodeName(SELF_NODE)
                .currentEndpoint(SELF_ENDPOINT)
                .leaseUntil(now.plus(Duration.ofMinutes(4))) // > 2m margin
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(doc));

        boolean result = service.tick(now);

        assertThat(result).isTrue();
        assertThat(service.isLocalPodMaster()).isTrue();
        verify(store, never()).renew(anyString(), anyString(), any());
        verify(store, never()).tryAcquire(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tick_iAmMaster_leaseAlmostUp_renews() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        // Lease ends within renewSafetyMargin → must renew.
        ClusterMasterDocument doc = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(SELF_POD).currentNodeName(SELF_NODE)
                .currentEndpoint(SELF_ENDPOINT)
                .leaseUntil(now.plus(Duration.ofMinutes(1))) // < 2m margin
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(doc));
        when(store.renew(eq(CLUSTER_ID), eq(SELF_POD), any(Instant.class)))
                .thenReturn(Optional.of(doc));

        boolean result = service.tick(now);

        assertThat(result).isTrue();
        verify(store).renew(eq(CLUSTER_ID), eq(SELF_POD), any(Instant.class));
    }

    @Test
    void tick_iAmMaster_renewLost_dropsRole() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        ClusterMasterDocument doc = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(SELF_POD).currentNodeName(SELF_NODE)
                .leaseUntil(now.plus(Duration.ofSeconds(30)))
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(doc));
        when(store.renew(eq(CLUSTER_ID), eq(SELF_POD), any(Instant.class)))
                .thenReturn(Optional.empty());  // race lost

        boolean result = service.tick(now);

        assertThat(result).isFalse();
        assertThat(service.isLocalPodMaster()).isFalse();
    }

    @Test
    void tick_otherHealthy_doesNothing() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        ClusterMasterDocument doc = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(OTHER_POD)
                .leaseUntil(now.plus(Duration.ofMinutes(4)))
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(doc));

        boolean result = service.tick(now);

        assertThat(result).isFalse();
        assertThat(service.isLocalPodMaster()).isFalse();
        verify(store, never()).renew(any(), any(), any());
        verify(store, never()).tryAcquire(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tick_otherExpired_steals() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        ClusterMasterDocument expired = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(OTHER_POD)
                .leaseUntil(now.minus(Duration.ofMinutes(1)))  // expired
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(expired));
        ClusterMasterDocument acquired = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(SELF_POD)
                .leaseUntil(now.plus(Duration.ofMinutes(5)))
                .build();
        when(store.tryAcquire(eq(CLUSTER_ID), eq(OTHER_POD), eq(SELF_POD), eq(SELF_NODE),
                eq(SELF_ENDPOINT), eq(now), any(Instant.class)))
                .thenReturn(Optional.of(acquired));

        boolean result = service.tick(now);

        assertThat(result).isTrue();
        assertThat(service.isLocalPodMaster()).isTrue();
    }

    @Test
    void tick_otherExpired_lostRace_skips() {
        Instant now = Instant.parse("2026-05-21T10:00:00Z");
        ClusterMasterDocument expired = ClusterMasterDocument.builder()
                .clusterId(CLUSTER_ID).currentPodId(OTHER_POD)
                .leaseUntil(now.minus(Duration.ofMinutes(1)))
                .build();
        when(store.find(CLUSTER_ID)).thenReturn(Optional.of(expired));
        when(store.tryAcquire(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        boolean result = service.tick(now);

        assertThat(result).isFalse();
        assertThat(service.isLocalPodMaster()).isFalse();
    }
}
