package de.mhus.vance.brain.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.rag.ProjectRagService;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.shared.project.ProjectActualState;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.role.RoleBootstrap;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@code bring} must decide "nothing to do" from what <em>this pod</em> has
 * started, not from {@code status}.
 *
 * <p>This is the defect the lease track exists for: {@code status} is shared
 * and, after a crash, still says {@code RUNNING} — the only thing that ever
 * writes it back is an explicit admin suspend. Short-circuiting on it left the
 * new lease holder owning a project it had never started: no workspace, no
 * session unbind, and no {@link ProjectEnginesStartRequested}, so Ursa
 * scheduler, hooks, tool preload and kit provisioning stayed dark.
 * See {@code planning/project-ownership-lease-design.md} §1.2.
 */
class ProjectLifecycleBringTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";

    private ProjectService projectService;
    private ProjectManagerService projectManager;
    private WorkspaceService workspaceService;
    private SessionService sessionService;
    private ApplicationEventPublisher eventPublisher;
    private ProjectActivationRegistry registry;
    private de.mhus.vance.brain.cluster.ClusterService clusterService;
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectManager = mock(ProjectManagerService.class);
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.mode()).thenReturn(de.mhus.vance.shared.workspace.WorkspaceMode.LOCAL);
        sessionService = mock(SessionService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new ProjectActivationRegistry();
        clusterService = mock(de.mhus.vance.brain.cluster.ClusterService.class);
        when(clusterService.selfPodId()).thenReturn("pod-1");

        ObjectProvider<ProjectRagService> rag = mock(ObjectProvider.class);
        ObjectProvider<SessionChatBootstrapper> chat = mock(ObjectProvider.class);
        ObjectProvider<EngineMessageRouter> router = mock(ObjectProvider.class);
        RoleBootstrap perms = mock(RoleBootstrap.class);

        service = new ProjectLifecycleService(
                projectService,
                projectManager,
                mock(de.mhus.vance.brain.cluster.placement.ProjectPlacementService.class),
                workspaceService,
                sessionService,
                eventPublisher,
                registry,
                rag,
                chat,
                router,
                perms,
                mock(de.mhus.vance.shared.project.ProjectQuotaService.class),
                mock(de.mhus.vance.shared.projectgroup.ProjectGroupService.class),
                clusterService,
                mock(de.mhus.vance.brain.cluster.ClusterBringClient.class),
                mock(ObjectProvider.class));
    }

    private void givenClaimedWithStatus(ProjectStatus status) {
        ProjectDocument doc = ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .build();
        when(projectManager.claimForLocalPod(TENANT, PROJECT)).thenReturn(doc);
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), any()))
                .thenReturn(doc);
        // What finishBring re-reads: the desired state is still RUNNING and the
        // lease is still ours.
        givenReread(ProjectStatus.RUNNING, true);
        when(sessionService.unbindAllForProjects(anyString(), anyList())).thenReturn(0L);
    }

    private void givenReread(ProjectStatus status, boolean ownedHere) {
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name(PROJECT)
                        .status(status)
                        .build()));
        when(projectManager.isOwnedByLocalPod(TENANT, PROJECT)).thenReturn(ownedHere);
    }

    @Test
    void runningStatusFromADeadPod_runsTheFullActivationPass() {
        // The crash-recovery case: status says RUNNING because a pod that no
        // longer exists wrote it, and this pod has started nothing.
        givenClaimedWithStatus(ProjectStatus.RUNNING);

        service.bring(TENANT, PROJECT);

        verify(workspaceService, times(1)).init(TENANT, PROJECT);
        verify(eventPublisher, times(1)).publishEvent(new ProjectEnginesStartRequested(TENANT, PROJECT));
        verify(sessionService, times(1)).unbindAllForProjects(eq(TENANT), anyList());
    }

    @Test
    void alreadyActiveOnThisPod_shortCircuits() {
        givenClaimedWithStatus(ProjectStatus.RUNNING);
        registry.activate(TENANT, PROJECT);

        service.bring(TENANT, PROJECT);

        verify(workspaceService, never()).init(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void suspendedProject_runsTheFullActivationPass() {
        givenClaimedWithStatus(ProjectStatus.SUSPENDED);

        service.bring(TENANT, PROJECT);

        verify(workspaceService, times(1)).init(TENANT, PROJECT);
        verify(eventPublisher, times(1)).publishEvent(new ProjectEnginesStartRequested(TENANT, PROJECT));
    }

    @Test
    void failedWorkspaceInit_leavesProjectUnactivatedSoTheNextBringRetries() {
        givenClaimedWithStatus(ProjectStatus.SUSPENDED);
        org.mockito.Mockito.doThrow(new IllegalStateException("disk full"))
                .when(workspaceService)
                .init(TENANT, PROJECT);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.bring(TENANT, PROJECT))
                .isInstanceOf(IllegalStateException.class);

        org.assertj.core.api.Assertions.assertThat(registry.isActive(TENANT, PROJECT))
                .isFalse();
    }

    /**
     * The desired state moves to RUNNING first, as a strict CAS from what the
     * bring read — so an explicit suspend or close that landed in between wins
     * over the implicit bring — and the actual state walks STARTING → RUNNING.
     */
    @Test
    void bring_movesDesiredStateFirst_thenRecordsStartingAndRunning() {
        givenClaimedWithStatus(ProjectStatus.IDLE);

        service.bring(TENANT, PROJECT);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(projectService, workspaceService);
        order.verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING);
        order.verify(projectService).setActualState(TENANT, PROJECT, "pod-1", ProjectActualState.STARTING);
        order.verify(workspaceService).init(TENANT, PROJECT);
        order.verify(projectService).setActualState(TENANT, PROJECT, "pod-1", ProjectActualState.RUNNING);
    }

    /**
     * An operator suspended the project while it was starting. The conflict
     * must surface — operator intent beats an implicit bring — and the actual
     * state must not be reported as RUNNING.
     */
    @Test
    void desiredStateChangedWhileStarting_surfacesTheConflict() {
        givenClaimedWithStatus(ProjectStatus.SUSPENDED);
        givenReread(ProjectStatus.SUSPENDED, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.bring(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);

        verify(projectService, never()).setActualState(TENANT, PROJECT, "pod-1", ProjectActualState.RUNNING);
    }

    /**
     * Being RUNNING in Mongo is only "done" while the lease is still here. If it
     * moved (expired during a slow start), the other pod runs the project — this
     * bring must not report success for a local start that the lease reconcile
     * is about to unload.
     */
    @Test
    void leaseMovedWhileStarting_throws() {
        givenClaimedWithStatus(ProjectStatus.SUSPENDED);
        givenReread(ProjectStatus.RUNNING, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.bring(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);

        verify(projectService, never()).setActualState(TENANT, PROJECT, "pod-1", ProjectActualState.RUNNING);
    }

    /**
     * Two callers on this pod (self-pull and a WS handler, say) bring the
     * same project at once. The second one must wait for the first and then
     * take the "already active here" short-circuit instead of running the
     * activation pass a second time.
     */
    @Test
    void concurrentBringsOnThisPod_activateOnce() throws Exception {
        ProjectDocument suspended = ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(ProjectStatus.SUSPENDED)
                .build();
        ProjectDocument running = ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(ProjectStatus.RUNNING)
                .build();
        when(projectManager.claimForLocalPod(TENANT, PROJECT)).thenReturn(suspended, running);
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), any()))
                .thenReturn(running);
        givenReread(ProjectStatus.RUNNING, true);
        when(sessionService.unbindAllForProjects(anyString(), anyList())).thenReturn(0L);

        Thread[] second = new Thread[1];
        java.util.concurrent.atomic.AtomicReference<Throwable> secondFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
                    // While the first bring is inside its activation pass,
                    // start the second one and give it time to run into the
                    // lock. Without the lock it would claim and init right now.
                    second[0] = new Thread(() -> {
                        try {
                            service.bring(TENANT, PROJECT);
                        } catch (Throwable t) {
                            secondFailure.set(t);
                        }
                    });
                    second[0].start();
                    second[0].join(300);
                    return null;
                })
                .doReturn(null)
                .when(workspaceService)
                .init(TENANT, PROJECT);

        service.bring(TENANT, PROJECT);
        second[0].join(5_000);

        org.assertj.core.api.Assertions.assertThat(second[0].isAlive()).isFalse();
        org.assertj.core.api.Assertions.assertThat(secondFailure.get()).isNull();
        verify(workspaceService, times(1)).init(TENANT, PROJECT);
        verify(eventPublisher, times(1)).publishEvent(new ProjectEnginesStartRequested(TENANT, PROJECT));
    }
}
