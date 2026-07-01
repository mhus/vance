package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Defensive guard around stale {@code homeNode} values on legacy
 * podless project documents (system projects whose names start with
 * {@code _}).
 *
 * <p>An older code path could set a Home Pod on {@code _user_<login>}
 * or {@code _vance} before the podless contract was tightened — that
 * stale value must never drive routing, otherwise engine-to-engine
 * dispatches for those projects pick the cross-pod path and fail to
 * reach the local handler. This test pins the contract by stubbing a
 * podless document with a non-blank {@code homeNode} and asserting
 * the lookup returns empty regardless.
 */
class ProjectManagerServicePodlessTest {

    @Test
    void findProjectEndpoint_podlessProjectWithStaleHomeNode_returnsEmpty() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);

        ProjectDocument legacy = ProjectDocument.builder()
                .tenantId("acme")
                .name("_user_wile.coyote")
                .homeNode("ghost-pod")
                .build();
        // Stub even though the fix should not consult the repository for
        // podless names — lenient() keeps the mock happy if behaviour
        // changes. Today: zero interactions is the assertion.
        lenient().when(projectService.findByTenantAndName("acme", "_user_wile.coyote"))
                .thenReturn(Optional.of(legacy));

        @SuppressWarnings("unchecked")
        ObjectProvider<ClusterMasterService> masterProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProjectLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        ProjectManagerService manager = new ProjectManagerService(
                projectService, clusterService,
                lifecycleProvider,
                mock(ClusterBringClient.class),
                masterProvider);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "_user_wile.coyote");

        assertThat(endpoint)
                .as("podless projects must always look local, regardless of stale homeNode")
                .isEmpty();
        // Belt-and-suspenders — if someone re-introduces a Mongo lookup
        // before the isPodless short-circuit, this catches the regression.
        verifyNoInteractions(projectService);
        verifyNoInteractions(clusterService);
    }

    @Test
    void findProjectEndpoint_normalProjectWithHomeNode_returnsResolvedEndpoint() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);

        ProjectDocument doc = ProjectDocument.builder()
                .tenantId("acme")
                .name("ferienhaus-versicherung")
                .homeNode("maya-prosser")
                .build();
        when(projectService.findByTenantAndName("acme", "ferienhaus-versicherung"))
                .thenReturn(Optional.of(doc));
        // findProjectEndpoint now gates the home node against the live set —
        // a stale home is treated as "no live home" (returns empty).
        when(clusterService.liveClusterNodeNames())
                .thenReturn(java.util.Set.of("maya-prosser"));
        when(clusterService.resolveEndpoint("maya-prosser"))
                .thenReturn(Optional.of("10.0.0.5:9990"));

        @SuppressWarnings("unchecked")
        ObjectProvider<ClusterMasterService> masterProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProjectLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        ProjectManagerService manager = new ProjectManagerService(
                projectService, clusterService,
                lifecycleProvider,
                mock(ClusterBringClient.class),
                masterProvider);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "ferienhaus-versicherung");

        assertThat(endpoint).contains("10.0.0.5:9990");
    }
}
