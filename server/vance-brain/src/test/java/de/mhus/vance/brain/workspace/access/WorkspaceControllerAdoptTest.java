package de.mhus.vance.brain.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.projects.WorkspaceTreeNodeDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adopt branch of {@code /workspace/tree}: what a read request is allowed
 * to do to a project nobody owns.
 *
 * <p>Two rules meet here and pull in opposite directions.
 *
 * <ul>
 *   <li><b>Adopting has to mean bringing.</b> A bare {@code claimForLocalPod}
 *       makes this pod the owner without activating the project, and the
 *       activation-gated hook / scheduler listeners then never fire on the pod
 *       that owns them — silently. It also leaves the workspace unmaterialised,
 *       so the read this path exists for still answers empty.</li>
 *   <li><b>Bringing must not resurrect a parked project.</b> {@code bring}
 *       transitions any non-RUNNING status to RUNNING. For an explicit admin
 *       bring that is the point; for a GET it would mean that opening a tab
 *       restarts a project somebody suspended on purpose — the same "suspend
 *       does not survive by itself" defect the recovery selector was narrowed
 *       to fix.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceControllerAdoptTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "roadrunner";

    @Mock private WorkspaceService workspaceService;
    @Mock private WorkspaceRoutingCache routingCache;
    @Mock private WorkspaceAccessProperties properties;
    @Mock private LocationService locationService;
    @Mock private ProjectLifecycleService lifecycleService;
    @Mock private ProjectService projectService;
    @Mock private RequestAuthority authority;

    private WorkspaceController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        // Read in the constructor — Mockito's Duration.ZERO default is rejected
        // by HttpClient.Builder, so it has to be stubbed before construction.
        when(properties.getConnectTimeout()).thenReturn(java.time.Duration.ofSeconds(2));
        controller = new WorkspaceController(
                workspaceService, routingCache, properties, locationService,
                lifecycleService, projectService, JsonMapper.builder().build(),
                authority, "tok");
        request = mock(HttpServletRequest.class);

        // Force the adopt branch: not bypassed, not self-owned, nobody live owns it.
        when(properties.isBypassProxy()).thenReturn(false);
        when(routingCache.isSelfOwned(new ProjectPodKey(TENANT, PROJECT))).thenReturn(false);
        when(routingCache.lookup(new ProjectPodKey(TENANT, PROJECT)))
                .thenReturn(Optional.empty());
    }

    @Test
    void unownedRunnableProject_isBroughtNotJustClaimed() {
        givenStatus(ProjectStatus.RUNNING);
        WorkspaceTreeNodeDto served = WorkspaceTreeNodeDto.builder()
                .name("").path("").build();
        when(workspaceService.treeRoot(anyString(), anyString(), anyInt()))
                .thenReturn(served);

        WorkspaceTreeNodeDto result = controller.tree(TENANT, PROJECT, null, 1, request);

        verify(lifecycleService).bring(TENANT, PROJECT);
        assertThat(result)
                .as("after a successful adopt the tree is served locally")
                .isSameAs(served);
    }

    @Test
    void suspendedProject_isNotResurrectedByARead() {
        givenStatus(ProjectStatus.SUSPENDED);

        WorkspaceTreeNodeDto result = controller.tree(TENANT, PROJECT, null, 1, request);

        verify(lifecycleService, never()).bring(anyString(), anyString());
        assertThat(result.getChildren())
                .as("a parked project answers with an empty root — its workspace "
                        + "is off-disk, which is also the truthful answer")
                .isEmpty();
    }

    @Test
    void closedProject_isNotAdoptedEither() {
        givenStatus(ProjectStatus.CLOSED);

        controller.tree(TENANT, PROJECT, null, 1, request);

        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void vanishedProject_isNotAdopted() {
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.empty());

        controller.tree(TENANT, PROJECT, null, 1, request);

        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    private void givenStatus(ProjectStatus status) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId(TENANT);
        doc.setName(PROJECT);
        doc.setStatus(status);
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.of(doc));
    }
}
