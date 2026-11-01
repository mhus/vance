package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.role.RoleBootstrap;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Archiving stops the project where it runs: locally through the stop
 * event, on another pod through that pod's release, and — when the holder
 * cannot be reached — by taking its lease so the holder unloads on its own.
 * CLOSED is written first, so nothing can claim the project mid-teardown.
 */
class ProjectLifecycleCloseTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";
    private static final String SELF = "pod-a";
    private static final String OTHER = "pod-b";

    private ProjectService projectService;
    private ProjectManagerService projectManager;
    private WorkspaceService workspaceService;
    private ApplicationEventPublisher eventPublisher;
    private ProjectActivationRegistry registry;
    private ClusterService clusterService;
    private ClusterBringClient bringClient;
    private SessionLifecycleService sessionLifecycle;
    private ObjectProvider<SessionLifecycleService> sessionLifecycleProvider;
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectManager = mock(ProjectManagerService.class);
        workspaceService = mock(WorkspaceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new ProjectActivationRegistry();
        clusterService = mock(ClusterService.class);
        bringClient = mock(ClusterBringClient.class);
        sessionLifecycle = mock(SessionLifecycleService.class);
        sessionLifecycleProvider = mock(ObjectProvider.class);
        when(sessionLifecycleProvider.getObject()).thenReturn(sessionLifecycle);
        when(clusterService.selfPodId()).thenReturn(SELF);
        when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        service = new ProjectLifecycleService(
                projectService,
                projectManager,
                mock(ProjectPlacementService.class),
                workspaceService,
                mock(SessionService.class),
                eventPublisher,
                registry,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(RoleBootstrap.class),
                mock(ProjectQuotaService.class),
                mock(ProjectGroupService.class),
                clusterService,
                mock(ProjectLiveService.class),
                bringClient,
                sessionLifecycleProvider);
    }

    private void closedWithOwner(String podId) {
        ProjectDocument doc = ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(ProjectStatus.CLOSED)
                .homePodId(podId)
                .homeNode(podId == null ? null : "node-" + podId)
                .claimedAt(podId == null ? null : Instant.now())
                .build();
        when(projectService.close(TENANT, PROJECT, "archived")).thenReturn(doc);
    }

    @Test
    void localHolder_stopsByEventAndDropsTheLease() {
        closedWithOwner(SELF);

        service.close(TENANT, PROJECT, "archived");

        InOrder order = inOrder(projectService, sessionLifecycle, eventPublisher, projectManager, workspaceService);
        order.verify(projectService).close(TENANT, PROJECT, "archived");
        order.verify(sessionLifecycle).suspendProjectSessions(TENANT, PROJECT);
        order.verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        order.verify(projectManager).releaseLocalLease(TENANT, PROJECT);
        order.verify(workspaceService).dispose(TENANT, PROJECT);
        verify(bringClient, never()).requestRelease(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void activatedHereWithoutLiveLease_stillStopsLocally() {
        closedWithOwner(null);
        registry.activate(TENANT, PROJECT);

        service.close(TENANT, PROJECT, "archived");

        verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
    }

    @Test
    void remoteHolder_isAskedToReleaseBeforeTheWorkspaceGoes() {
        closedWithOwner(OTHER);
        when(clusterService.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("10.0.0.2:8080"));
        when(bringClient.requestRelease("10.0.0.2:8080", TENANT, PROJECT, true)).thenReturn(true);

        service.close(TENANT, PROJECT, "archived");

        InOrder order = inOrder(projectService, bringClient, workspaceService);
        order.verify(projectService).close(TENANT, PROJECT, "archived");
        order.verify(bringClient).requestRelease("10.0.0.2:8080", TENANT, PROJECT, true);
        order.verify(workspaceService).dispose(TENANT, PROJECT);
        verify(eventPublisher, never()).publishEvent(any());
        verify(sessionLifecycle, never()).suspendProjectSessions(anyString(), anyString());
        verify(sessionLifecycle, never()).suspendProjectSessionsOffLane(anyString(), anyString());
        verify(projectService, never()).releaseLease(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unreachableHolder_losesItsLease() {
        closedWithOwner(OTHER);
        when(clusterService.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("10.0.0.2:8080"));
        when(bringClient.requestRelease("10.0.0.2:8080", TENANT, PROJECT, true))
                .thenThrow(new ClusterBringClient.ClusterBringException("connection refused"));

        service.close(TENANT, PROJECT, "archived");

        verify(sessionLifecycle).suspendProjectSessionsOffLane(TENANT, PROJECT);
        verify(projectService).releaseLease(TENANT, PROJECT, OTHER, "node-" + OTHER, "10.0.0.2:8080");
        verify(workspaceService).dispose(TENANT, PROJECT);
    }

    @Test
    void holderDenyingIt_losesTheLeaseAnyway() {
        closedWithOwner(OTHER);
        when(clusterService.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("10.0.0.2:8080"));
        when(bringClient.requestRelease("10.0.0.2:8080", TENANT, PROJECT, true)).thenReturn(false);

        service.close(TENANT, PROJECT, "archived");

        verify(projectService).releaseLease(TENANT, PROJECT, OTHER, "node-" + OTHER, "10.0.0.2:8080");
    }

    @Test
    void holderWithoutEndpoint_losesItsLease() {
        closedWithOwner(OTHER);
        when(clusterService.resolveEndpointByPodId(OTHER)).thenReturn(Optional.empty());

        service.close(TENANT, PROJECT, "archived");

        verify(projectService).releaseLease(TENANT, PROJECT, OTHER, "node-" + OTHER, "");
        verify(bringClient, never()).requestRelease(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void unowned_stopsNothing() {
        closedWithOwner(null);

        service.close(TENANT, PROJECT, "archived");

        verify(eventPublisher, never()).publishEvent(any());
        verify(bringClient, never()).requestRelease(anyString(), anyString(), anyString(), anyBoolean());
        verify(workspaceService).dispose(TENANT, PROJECT);
    }

    @Test
    void systemProject_touchesNothing() {
        assertThatThrownBy(() -> service.close(TENANT, "_user_alice", "archived"))
                .isInstanceOf(ProjectService.SystemProjectProtectedException.class);

        verify(projectService, never()).close(anyString(), anyString(), anyString());
        verify(workspaceService, never()).dispose(anyString(), anyString());
    }

    @Test
    void releaseOfAClosedProject_disposesTheWorkspace() {
        registry.activate(TENANT, PROJECT);
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name(PROJECT)
                        .status(ProjectStatus.CLOSED)
                        .build()));

        service.release(TENANT, PROJECT, true);

        verify(workspaceService).dispose(TENANT, PROJECT);
        verify(projectManager).releaseLocalLease(TENANT, PROJECT);
    }

    @Test
    void releaseOfARunningProject_keepsTheWorkspace() {
        registry.activate(TENANT, PROJECT);
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name(PROJECT)
                        .status(ProjectStatus.RUNNING)
                        .build()));

        service.release(TENANT, PROJECT, false);

        verify(workspaceService, never()).dispose(anyString(), anyString());
        verify(projectManager).releaseLocalLease(TENANT, PROJECT);
    }
}
