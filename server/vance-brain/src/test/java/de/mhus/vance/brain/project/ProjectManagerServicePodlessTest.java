package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
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
 * Defensive guard around leftover ownership values on legacy podless project
 * documents (system projects whose names start with {@code _}).
 *
 * <p>An older code path could set a Home Pod on {@code _user_<login>}
 * or {@code _vance} before the podless contract was tightened — that
 * stale value must never drive routing, otherwise engine-to-engine
 * dispatches for those projects pick the cross-pod path and fail to
 * reach the local handler. This test pins the contract by stubbing a
 * podless document that carries ownership fields and asserting
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
                .homePodId("pod-ghost")
                .homeNode("ghost-pod")
                .claimedAt(Instant.now())
                .build();
        // Stub even though the fix should not consult the repository for
        // podless names — lenient() keeps the mock happy if behaviour
        // changes. Today: zero interactions is the assertion.
        lenient().when(projectService.findByTenantAndName("acme", "_user_wile.coyote"))
                .thenReturn(Optional.of(legacy));

        ProjectManagerService manager =
                new ProjectManagerService(projectService, clusterService);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "_user_wile.coyote");

        assertThat(endpoint)
                .as("podless projects must always look local, even holding a valid-looking lease")
                .isEmpty();
        // Belt-and-suspenders — if someone re-introduces a Mongo lookup
        // before the isPodless short-circuit, this catches the regression.
        verifyNoInteractions(projectService);
        verifyNoInteractions(clusterService);
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
        when(clusterService.resolveEndpointByPodId("pod-maya"))
                .thenReturn(Optional.of("10.0.0.5:9990"));

        ProjectManagerService manager =
                new ProjectManagerService(projectService, clusterService);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "ferienhaus-versicherung");

        assertThat(endpoint).contains("10.0.0.5:9990");
    }
}
