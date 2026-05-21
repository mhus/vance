package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pick-pod + dispatch-bring scenarios for the placement primitive shared
 * by the Cluster-Master spawn endpoint and the distributor — see
 * {@code specification/cluster-project-management.md} §5.
 */
class ClusterPlacementServiceTest {

    private ClusterService clusterService;
    private ProjectService projectService;
    private ProjectLifecycleService lifecycleService;
    private ClusterBringClient bringClient;
    private ClusterPlacementService placement;

    @BeforeEach
    void setUp() {
        clusterService = mock(ClusterService.class);
        projectService = mock(ProjectService.class);
        lifecycleService = mock(ProjectLifecycleService.class);
        bringClient = mock(ClusterBringClient.class);
        placement = new ClusterPlacementService(
                clusterService, projectService, lifecycleService, bringClient);
        lenient().when(clusterService.selfNodeName()).thenReturn("self-node");
    }

    @Test
    void placeProject_emptyPods_throwsClusterFull() {
        when(clusterService.liveClusterPods()).thenReturn(List.of());
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("p1").homeResourceScore(1).build();

        assertThatThrownBy(() -> placement.placeProject(project))
                .isInstanceOf(ClusterMasterService.ClusterFullException.class);
    }

    @Test
    void placeProject_allFull_throwsClusterFull() {
        BrainPodDocument pod = BrainPodDocument.builder()
                .nodeName("pod-a").endpoint("a:1").resourcesCurrentScore(9999)
                .resourcesMaxScore(10000).build();
        when(clusterService.liveClusterPods()).thenReturn(List.of(pod));
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("big").homeResourceScore(500).build();

        assertThatThrownBy(() -> placement.placeProject(project))
                .isInstanceOf(ClusterMasterService.ClusterFullException.class);
    }

    @Test
    void placeProject_localTarget_dispatchesLocally() {
        BrainPodDocument self = BrainPodDocument.builder()
                .nodeName("self-node").endpoint("self:1").resourcesCurrentScore(10)
                .resourcesMaxScore(100).build();
        when(clusterService.liveClusterPods()).thenReturn(List.of(self));
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("p1").homeResourceScore(5).build();

        BrainPodDocument target = placement.placeProject(project);

        assertThat(target.getNodeName()).isEqualTo("self-node");
        verify(lifecycleService).bring("acme", "p1");
        verify(bringClient, never()).requestBring(anyString(), anyString(), anyString());
    }

    @Test
    void placeProject_remoteTarget_dispatchesViaBringClient() {
        BrainPodDocument other = BrainPodDocument.builder()
                .nodeName("other-node").endpoint("other:1").resourcesCurrentScore(10)
                .resourcesMaxScore(100).build();
        when(clusterService.liveClusterPods()).thenReturn(List.of(other));
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("p1").homeResourceScore(5).build();

        BrainPodDocument target = placement.placeProject(project);

        assertThat(target.getNodeName()).isEqualTo("other-node");
        verify(bringClient).requestBring("other:1", "acme", "p1");
        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void pickTarget_picksLeastLoadedFit() {
        BrainPodDocument hot = BrainPodDocument.builder()
                .nodeName("hot").resourcesCurrentScore(80).resourcesMaxScore(100).build();
        BrainPodDocument cold = BrainPodDocument.builder()
                .nodeName("cold").resourcesCurrentScore(10).resourcesMaxScore(100).build();
        // liveClusterPods returns sorted-by-load already
        BrainPodDocument picked = placement.pickTarget(List.of(cold, hot), 5);
        assertThat(picked.getNodeName()).isEqualTo("cold");
    }

    @Test
    void pickTarget_skipsPodWithoutRoom() {
        BrainPodDocument tight = BrainPodDocument.builder()
                .nodeName("tight").resourcesCurrentScore(95).resourcesMaxScore(100).build();
        BrainPodDocument roomy = BrainPodDocument.builder()
                .nodeName("roomy").resourcesCurrentScore(50).resourcesMaxScore(100).build();
        // Even though tight is lighter-loaded, it can't fit score=10 → roomy wins.
        BrainPodDocument picked = placement.pickTarget(List.of(tight, roomy), 10);
        assertThat(picked.getNodeName()).isEqualTo("roomy");
    }
}
