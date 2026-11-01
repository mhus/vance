package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.project.ProjectActualState;
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
 * {@code suspend} and {@code idle} write the <em>desired</em> state first, then take
 * the project down wherever it runs and drop its lease, and they leave the workspace
 * alone. They differ in the value they write — which decides who may wake the project
 * again — and in whether the open sessions are suspended with it.
 */
class ProjectLifecycleStopTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String POD = "pod-1";
    private static final String OTHER_POD = "pod-2";
    private static final String OTHER_ENDPOINT = "10.0.0.2:8080";

    private ProjectService projectService;
    private ProjectManagerService projectManager;
    private WorkspaceService workspaceService;
    private ApplicationEventPublisher eventPublisher;
    private ClusterService clusterService;
    private ClusterBringClient bringClient;
    private SessionLifecycleService sessionLifecycle;
    private ProjectActivationRegistry registry;
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectManager = mock(ProjectManagerService.class);
        workspaceService = mock(WorkspaceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        bringClient = mock(ClusterBringClient.class);
        sessionLifecycle = mock(SessionLifecycleService.class);
        registry = new ProjectActivationRegistry();
        clusterService = mock(ClusterService.class);
        when(clusterService.selfPodId()).thenReturn(POD);
        when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        ObjectProvider<SessionLifecycleService> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getObject()).thenReturn(sessionLifecycle);

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
                bringClient,
                sessionProvider);
    }

    private static ProjectDocument doc(ProjectStatus status) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .build();
    }

    private static ProjectDocument heldBy(String podId, ProjectStatus status) {
        ProjectDocument d = doc(status);
        d.setHomePodId(podId);
        d.setHomeNode("node-of-" + podId);
        d.setClaimedAt(Instant.now());
        return d;
    }

    private void given(ProjectDocument existing) {
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.of(existing));
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), any()))
                .thenAnswer(inv -> {
                    ProjectDocument moved =
                            heldBy(existing.getHomePodId() == null ? POD : existing.getHomePodId(), inv.getArgument(3));
                    moved.setHomePodId(existing.getHomePodId());
                    return moved;
                });
    }

    @Test
    void suspend_writesTheDesiredStateFirst_thenStopsHereAndDropsTheLease() {
        given(doc(ProjectStatus.RUNNING));
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        InOrder order = inOrder(projectService, eventPublisher, projectManager);
        order.verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.RUNNING, ProjectStatus.SUSPENDED);
        order.verify(projectService).setActualState(TENANT, PROJECT, POD, ProjectActualState.STOPPING);
        order.verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        order.verify(projectManager).releaseLocalLease(TENANT, PROJECT);
        assertThat(registry.isActive(TENANT, PROJECT)).isFalse();
    }

    @Test
    void suspend_leavesTheSessionsAlone() {
        given(doc(ProjectStatus.RUNNING));
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        verify(sessionLifecycle, never()).suspendProjectSessions(anyString(), anyString());
    }

    @Test
    void idle_isTheSameTeardownWithTheIdleDesiredState_andSuspendsTheSessions() {
        given(doc(ProjectStatus.RUNNING));
        registry.activate(TENANT, PROJECT);

        service.idle(TENANT, PROJECT);

        verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.RUNNING, ProjectStatus.IDLE);
        verify(sessionLifecycle).suspendProjectSessions(TENANT, PROJECT);
        verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        verify(projectManager).releaseLocalLease(TENANT, PROJECT);
    }

    @Test
    void neverTouchesTheWorkspace() {
        given(doc(ProjectStatus.RUNNING));
        registry.activate(TENANT, PROJECT);

        service.idle(TENANT, PROJECT);

        org.mockito.Mockito.verifyNoInteractions(workspaceService);
    }

    @Test
    void idle_reachesTheHolderOnAnotherPod_andDoesNotUnloadAnythingHere() {
        given(heldBy(OTHER_POD, ProjectStatus.RUNNING));
        when(clusterService.resolveEndpointByPodId(OTHER_POD)).thenReturn(Optional.of(OTHER_ENDPOINT));
        when(bringClient.requestRelease(OTHER_ENDPOINT, TENANT, PROJECT, true)).thenReturn(true);

        service.idle(TENANT, PROJECT);

        verify(bringClient).requestRelease(OTHER_ENDPOINT, TENANT, PROJECT, true);
        verify(eventPublisher, never()).publishEvent(any());
        verify(projectManager, never()).releaseLocalLease(anyString(), anyString());
    }

    @Test
    void suspend_asksTheHolderWithoutSuspendingSessions() {
        given(heldBy(OTHER_POD, ProjectStatus.RUNNING));
        when(clusterService.resolveEndpointByPodId(OTHER_POD)).thenReturn(Optional.of(OTHER_ENDPOINT));
        when(bringClient.requestRelease(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);

        service.suspend(TENANT, PROJECT);

        verify(bringClient).requestRelease(OTHER_ENDPOINT, TENANT, PROJECT, false);
    }

    @Test
    void idle_holderUnreachable_takesTheLeaseAwaySoItUnloadsOnItsNextRenewal() {
        given(heldBy(OTHER_POD, ProjectStatus.RUNNING));
        when(clusterService.resolveEndpointByPodId(OTHER_POD)).thenReturn(Optional.of(OTHER_ENDPOINT));
        when(bringClient.requestRelease(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new ClusterBringClient.ClusterBringException("connect timed out", null));

        service.idle(TENANT, PROJECT);

        verify(sessionLifecycle).suspendProjectSessionsOffLane(TENANT, PROJECT);
        verify(projectService).releaseLease(eq(TENANT), eq(PROJECT), eq(OTHER_POD), anyString(), anyString());
    }

    @Test
    void alreadyIdleAndRunningNowhere_isANoOp() {
        given(doc(ProjectStatus.IDLE));

        service.idle(TENANT, PROJECT);

        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void alreadySuspendedButStillUpHere_finishesTheTeardown() {
        // An earlier suspend wrote the desired state and then failed in a listener.
        given(doc(ProjectStatus.SUSPENDED));
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
        verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        verify(projectManager).releaseLocalLease(TENANT, PROJECT);
        assertThat(registry.isActive(TENANT, PROJECT)).isFalse();
    }

    @Test
    void aClosedProjectCannotBeStopped() {
        given(doc(ProjectStatus.CLOSED));

        assertThatThrownBy(() -> service.suspend(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
        assertThatThrownBy(() -> service.idle(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
    }

    @Test
    void aNeverBroughtProjectMayBeSetIdleDirectly() {
        given(doc(ProjectStatus.INIT));

        service.idle(TENANT, PROJECT);

        verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.INIT, ProjectStatus.IDLE);
    }
}
