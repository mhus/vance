package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.role.RoleBootstrap;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@code suspend} and {@code idle} write the <em>desired</em> state first and
 * only then take the project down, and they leave the workspace alone. They
 * differ in the value they write — which is what decides who may wake the
 * project again.
 */
class ProjectLifecycleStopTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String POD = "pod-1";

    private ProjectService projectService;
    private WorkspaceService workspaceService;
    private ApplicationEventPublisher eventPublisher;
    private ProjectActivationRegistry registry;
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        workspaceService = mock(WorkspaceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new ProjectActivationRegistry();
        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.selfPodId()).thenReturn(POD);

        service = new ProjectLifecycleService(
                projectService,
                mock(ProjectManagerService.class),
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
                mock(ClusterBringClient.class),
                mock(ObjectProvider.class));
    }

    private static ProjectDocument doc(ProjectStatus status) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .build();
    }

    private void given(ProjectStatus status) {
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.of(doc(status)));
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), any()))
                .thenAnswer(inv -> doc(inv.getArgument(3)));
    }

    @Test
    void suspend_writesTheDesiredStateFirst_thenStopsAndRecordsStopped() {
        given(ProjectStatus.RUNNING);
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        InOrder order = inOrder(projectService, eventPublisher);
        order.verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.RUNNING, ProjectStatus.SUSPENDED);
        order.verify(projectService).setActualState(TENANT, PROJECT, POD, ProjectActualState.STOPPING);
        order.verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        order.verify(projectService).setActualState(TENANT, PROJECT, POD, ProjectActualState.STOPPED);
        assertThat(registry.isActive(TENANT, PROJECT)).isFalse();
    }

    @Test
    void idle_isTheSameTeardownWithTheIdleDesiredState() {
        given(ProjectStatus.RUNNING);
        registry.activate(TENANT, PROJECT);

        service.idle(TENANT, PROJECT);

        verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.RUNNING, ProjectStatus.IDLE);
        verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        verify(projectService).setActualState(TENANT, PROJECT, POD, ProjectActualState.STOPPED);
    }

    @Test
    void neverTouchesTheWorkspace() {
        given(ProjectStatus.RUNNING);
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        org.mockito.Mockito.verifyNoInteractions(workspaceService);
    }

    @Test
    void alreadySuspendedAndNotRunningHere_isANoOp() {
        given(ProjectStatus.SUSPENDED);

        service.suspend(TENANT, PROJECT);

        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void alreadySuspendedButStillUpHere_finishesTheTeardown() {
        // An earlier suspend wrote the desired state and then failed in a listener.
        given(ProjectStatus.SUSPENDED);
        registry.activate(TENANT, PROJECT);

        service.suspend(TENANT, PROJECT);

        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
        verify(eventPublisher).publishEvent(new ProjectEnginesStopRequested(TENANT, PROJECT));
        assertThat(registry.isActive(TENANT, PROJECT)).isFalse();
    }

    @Test
    void aClosedProjectCannotBeStopped() {
        given(ProjectStatus.CLOSED);

        assertThatThrownBy(() -> service.suspend(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
        assertThatThrownBy(() -> service.idle(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
    }

    @Test
    void aNeverBroughtProjectMayBeSuspendedDirectly() {
        given(ProjectStatus.INIT);

        service.suspend(TENANT, PROJECT);

        verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.INIT, ProjectStatus.SUSPENDED);
    }
}
