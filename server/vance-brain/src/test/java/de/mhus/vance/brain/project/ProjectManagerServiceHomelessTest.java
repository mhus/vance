package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Only the tenant project is homeless. It is never placed, so it never has an
 * endpoint and can never be claimed; the personal hubs {@code _user_<login>}
 * are placed through a lease like every other project.
 */
class ProjectManagerServiceHomelessTest {

    @Test
    void findProjectEndpoint_tenantProject_isEmptyWithoutLookingAnythingUp() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);
        ProjectManagerService manager = new ProjectManagerService(projectService, clusterService);

        assertThat(manager.findProjectEndpoint("acme", ProjectService.TENANT_PROJECT_NAME))
                .isEmpty();
        verifyNoInteractions(projectService);
        verifyNoInteractions(clusterService);
    }

    @Test
    void claimForLocalPod_tenantProject_isRefused() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);
        ProjectManagerService manager = new ProjectManagerService(projectService, clusterService);

        assertThatThrownBy(() -> manager.claimForLocalPod("acme", ProjectService.TENANT_PROJECT_NAME))
                .isInstanceOf(ProjectManagerService.ClaimRejectedException.class);
        verifyNoInteractions(projectService);
    }

    @Test
    void findProjectEndpoint_userHubWithValidLease_returnsItsHomePod() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);

        ProjectDocument hub = ProjectDocument.builder()
                .tenantId("acme")
                .name("_user_wile.coyote")
                .homePodId("pod-maya")
                .homeNode("maya-prosser")
                .claimedAt(Instant.now())
                .build();
        when(projectService.findByTenantAndName("acme", "_user_wile.coyote")).thenReturn(Optional.of(hub));
        when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        when(clusterService.resolveEndpointByPodId("pod-maya")).thenReturn(Optional.of("10.0.0.5:9990"));

        ProjectManagerService manager = new ProjectManagerService(projectService, clusterService);

        assertThat(manager.findProjectEndpoint("acme", "_user_wile.coyote")).contains("10.0.0.5:9990");
    }

    @Test
    void findProjectEndpoint_normalProjectWithValidLease_returnsResolvedEndpoint() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);

        ProjectDocument doc = ProjectDocument.builder()
                .tenantId("acme")
                .name("ferienhaus-versicherung")
                .homePodId("pod-maya")
                .homeNode("maya-prosser")
                .claimedAt(Instant.now())
                .build();
        when(projectService.findByTenantAndName("acme", "ferienhaus-versicherung"))
                .thenReturn(Optional.of(doc));
        // The lease on the document decides liveness; the cluster registry is
        // only asked where the holding pod is.
        when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        when(clusterService.resolveEndpointByPodId("pod-maya")).thenReturn(Optional.of("10.0.0.5:9990"));

        ProjectManagerService manager = new ProjectManagerService(projectService, clusterService);

        assertThat(manager.findProjectEndpoint("acme", "ferienhaus-versicherung"))
                .contains("10.0.0.5:9990");
    }
}
