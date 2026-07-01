package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectManagerService#findProjectEndpoint} must not route to a
 * home pod that has gone stale — otherwise a session whose home crashed
 * (or moved host IP) stays permanently unreachable, every resume tunnelled
 * to a dead endpoint (observed 2026-07-01).
 */
class ProjectManagerServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final ClusterService clusterService = mock(ClusterService.class);
    private final ProjectManagerService manager = new ProjectManagerService(
            projectService, clusterService, null, null, null);

    private void givenProjectHome(String node) {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme").name("test1").homeNode(node).build()));
    }

    @Test
    void liveHomeNode_resolvesToEndpoint() {
        givenProjectHome("naga-vorlon");
        when(clusterService.liveClusterNodeNames()).thenReturn(Set.of("naga-vorlon"));
        when(clusterService.resolveEndpoint("naga-vorlon")).thenReturn(Optional.of("192.168.1.113:9991"));

        assertThat(manager.findProjectEndpoint("acme", "test1")).contains("192.168.1.113:9991");
    }

    @Test
    void staleHomeNode_returnsEmptySoTheWsFallsBackToLocal() {
        givenProjectHome("dead-node");
        // dead-node exists in brain_pods (resolveEndpoint would still return it)
        // but is NOT in the live set — the whole point of the fix.
        when(clusterService.liveClusterNodeNames()).thenReturn(Set.of("naga-vorlon"));
        lenient().when(clusterService.resolveEndpoint("dead-node")).thenReturn(Optional.of("10.5.30.87:9991"));

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void rawEndpointHome_isTrustedWithoutNodeNameLivenessCheck() {
        givenProjectHome("10.9.9.9:9991");
        when(clusterService.resolveEndpoint("10.9.9.9:9991")).thenReturn(Optional.of("10.9.9.9:9991"));

        assertThat(manager.findProjectEndpoint("acme", "test1")).contains("10.9.9.9:9991");
    }

    @Test
    void unclaimedProject_returnsEmpty() {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme").name("test1").homeNode(null).build()));

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }
}
