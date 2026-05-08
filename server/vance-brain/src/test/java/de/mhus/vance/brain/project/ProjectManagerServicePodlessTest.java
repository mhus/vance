package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Defensive guard around stale {@code podIp} values on legacy podless
 * project documents (system projects whose names start with {@code _}).
 *
 * <p>An older code path could set {@code podIp} on {@code _user_<login>}
 * or {@code _vance} before the podless contract was tightened — that
 * stale value must never drive routing, otherwise engine-to-engine
 * dispatches for those projects pick the cross-pod path and fail to
 * reach the local handler. This test pins the contract by stubbing a
 * podless document with a non-blank {@code podIp} and asserting the
 * lookup returns empty regardless.
 */
class ProjectManagerServicePodlessTest {

    @Test
    void findProjectEndpoint_podlessProjectWithStalePodIp_returnsEmpty() {
        ProjectService projectService = mock(ProjectService.class);
        LocationService locationService = mock(LocationService.class);

        ProjectDocument legacy = ProjectDocument.builder()
                .tenantId("acme")
                .name("_user_wile.coyote")
                .podIp("212.9.61.37:9990")
                .build();
        // Stub even though the fix should not consult the repository for
        // podless names — lenient() keeps the mock happy if behaviour
        // changes. Today: zero interactions is the assertion.
        lenient().when(projectService.findByTenantAndName("acme", "_user_wile.coyote"))
                .thenReturn(Optional.of(legacy));

        ProjectManagerService manager = new ProjectManagerService(projectService, locationService);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "_user_wile.coyote");

        assertThat(endpoint)
                .as("podless projects must always look local, regardless of stale podIp")
                .isEmpty();
        // Belt-and-suspenders — if someone re-introduces a Mongo lookup
        // before the isPodless short-circuit, this catches the regression.
        verifyNoInteractions(projectService);
    }

    @Test
    void findProjectEndpoint_normalProjectWithPodIp_returnsEndpoint() {
        ProjectService projectService = mock(ProjectService.class);
        LocationService locationService = mock(LocationService.class);

        ProjectDocument doc = ProjectDocument.builder()
                .tenantId("acme")
                .name("ferienhaus-versicherung")
                .podIp("10.0.0.5:9990")
                .build();
        when(projectService.findByTenantAndName("acme", "ferienhaus-versicherung"))
                .thenReturn(Optional.of(doc));

        ProjectManagerService manager = new ProjectManagerService(projectService, locationService);

        Optional<String> endpoint = manager.findProjectEndpoint("acme", "ferienhaus-versicherung");

        assertThat(endpoint).contains("10.0.0.5:9990");
    }
}
