package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ClusterCleanupTick#sweep(Instant)} directly so all branches
 * are reachable without the scheduler — same pattern as
 * {@link ClusterMasterServiceTest}.
 */
class ClusterCleanupTickTest {

    private static final String CLUSTER_ID = "default";
    private static final String SELF_POD = "self-pod-id";
    private static final String SELF_NODE = "self-node";

    private ClusterMasterService masterService;
    private ClusterService clusterService;
    private BrainPodService brainPodService;
    private ClusterProperties properties;
    private ClusterCleanupTick tick;

    @BeforeEach
    void setUp() {
        masterService = mock(ClusterMasterService.class);
        clusterService = mock(ClusterService.class);
        brainPodService = mock(BrainPodService.class);
        properties = new ClusterProperties();
        properties.getCleanup().setAfter(Duration.ofHours(1));

        lenient().when(clusterService.selfClusterId()).thenReturn(CLUSTER_ID);
        lenient().when(clusterService.selfPodId()).thenReturn(SELF_POD);
        lenient().when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        lenient().when(brainPodService.deleteByPodId(anyString())).thenReturn(1L);

        tick = new ClusterCleanupTick(masterService, clusterService, properties, brainPodService);
    }

    @Test
    void sweep_deletesStaleRow() {
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        BrainPodDocument stale = pod("old-pod", "shannon-curie", PodStatus.STOPPED,
                now.minus(Duration.ofHours(2)));
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of(stale));

        int deleted = tick.sweep(now);

        assertThat(deleted).isEqualTo(1);
        verify(brainPodService).deleteByPodId("old-pod");
    }

    @Test
    void sweep_keepsFreshRow() {
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        BrainPodDocument fresh = pod("fresh-pod", "mira-narwhal", PodStatus.RUNNING,
                now.minus(Duration.ofMinutes(2)));
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of(fresh));

        int deleted = tick.sweep(now);

        assertThat(deleted).isZero();
        verify(brainPodService, never()).deleteByPodId(anyString());
    }

    @Test
    void sweep_neverDeletesSelf() {
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        // Self has an absurdly old heartbeat (e.g. JVM was paused under debugger).
        BrainPodDocument self = pod(SELF_POD, SELF_NODE, PodStatus.RUNNING,
                now.minus(Duration.ofDays(7)));
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of(self));

        int deleted = tick.sweep(now);

        assertThat(deleted).isZero();
        verify(brainPodService, never()).deleteByPodId(anyString());
    }

    @Test
    void sweep_ignoresStatus_deletesStaleRunning() {
        // A crashed pod stays at RUNNING but stops beating — must still be purged.
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        BrainPodDocument crashed = pod("crashed-pod", "zombie-pod", PodStatus.RUNNING,
                now.minus(Duration.ofHours(2)));
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of(crashed));

        int deleted = tick.sweep(now);

        assertThat(deleted).isEqualTo(1);
        verify(brainPodService).deleteByPodId("crashed-pod");
    }

    @Test
    void sweep_keepsRowWithoutHeartbeat() {
        // Just registered, hasn't beaten yet — grace period.
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        BrainPodDocument newborn = pod("new-pod", "fresh-name", PodStatus.STARTING, null);
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of(newborn));

        int deleted = tick.sweep(now);

        assertThat(deleted).isZero();
        verify(brainPodService, never()).deleteByPodId(anyString());
    }

    @Test
    void sweep_mixedBatch_deletesOnlyStaleOthers() {
        Instant now = Instant.parse("2026-06-09T11:00:00Z");
        BrainPodDocument self = pod(SELF_POD, SELF_NODE, PodStatus.RUNNING,
                now.minus(Duration.ofSeconds(30)));
        BrainPodDocument fresh = pod("fresh-pod", "fresh-name", PodStatus.RUNNING,
                now.minus(Duration.ofMinutes(2)));
        BrainPodDocument stale1 = pod("stale-1", "stale-name-1", PodStatus.STOPPED,
                now.minus(Duration.ofHours(3)));
        BrainPodDocument stale2 = pod("stale-2", "stale-name-2", PodStatus.RUNNING,
                now.minus(Duration.ofHours(5)));
        when(brainPodService.listCluster(CLUSTER_ID))
                .thenReturn(List.of(self, fresh, stale1, stale2));

        int deleted = tick.sweep(now);

        assertThat(deleted).isEqualTo(2);
        verify(brainPodService).deleteByPodId("stale-1");
        verify(brainPodService).deleteByPodId("stale-2");
        verify(brainPodService, never()).deleteByPodId(SELF_POD);
        verify(brainPodService, never()).deleteByPodId("fresh-pod");
    }

    @Test
    void tick_noopWhenNotMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(false);

        tick.tick();

        verify(brainPodService, never()).listCluster(anyString());
        verify(brainPodService, never()).deleteByPodId(anyString());
    }

    @Test
    void tick_runsWhenMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(brainPodService.listCluster(CLUSTER_ID)).thenReturn(List.of());

        tick.tick();

        verify(brainPodService).listCluster(CLUSTER_ID);
    }

    private BrainPodDocument pod(String podId, String nodeName, PodStatus status,
                                 @org.jspecify.annotations.Nullable Instant beat) {
        return BrainPodDocument.builder()
                .clusterId(CLUSTER_ID)
                .podId(podId)
                .nodeName(nodeName)
                .endpoint("10.0.0.1:9990")
                .status(status)
                .lastHeartbeatAt(beat)
                .build();
    }
}
