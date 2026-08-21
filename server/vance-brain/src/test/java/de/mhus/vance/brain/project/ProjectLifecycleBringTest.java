package de.mhus.vance.brain.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.rag.ProjectRagService;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.workspace.WorkspaceService;
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
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectManager = mock(ProjectManagerService.class);
        workspaceService = mock(WorkspaceService.class);
        sessionService = mock(SessionService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new ProjectActivationRegistry();

        ObjectProvider<ProjectRagService> rag = mock(ObjectProvider.class);
        ObjectProvider<SessionChatBootstrapper> chat = mock(ObjectProvider.class);
        ObjectProvider<EngineMessageRouter> router = mock(ObjectProvider.class);
        ObjectProvider<PermissionBootstrap> perms = mock(ObjectProvider.class);

        service = new ProjectLifecycleService(
                projectService, projectManager, workspaceService, sessionService,
                eventPublisher, registry, rag, chat, router, perms);
    }

    private void givenClaimedWithStatus(ProjectStatus status) {
        ProjectDocument doc = ProjectDocument.builder()
                .tenantId(TENANT).name(PROJECT).status(status).build();
        when(projectManager.claimForLocalPod(TENANT, PROJECT)).thenReturn(doc);
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), any()))
                .thenReturn(doc);
        when(sessionService.unbindAllForProjects(anyList())).thenReturn(0L);
    }

    @Test
    void runningStatusFromADeadPod_runsTheFullActivationPass() {
        // The crash-recovery case: status says RUNNING because a pod that no
        // longer exists wrote it, and this pod has started nothing.
        givenClaimedWithStatus(ProjectStatus.RUNNING);

        service.bring(TENANT, PROJECT);

        verify(workspaceService, times(1)).init(TENANT, PROJECT);
        verify(eventPublisher, times(1))
                .publishEvent(new ProjectEnginesStartRequested(TENANT, PROJECT));
        verify(sessionService, times(1)).unbindAllForProjects(anyList());
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
        verify(eventPublisher, times(1))
                .publishEvent(new ProjectEnginesStartRequested(TENANT, PROJECT));
    }

    @Test
    void failedWorkspaceInit_leavesProjectUnactivatedSoTheNextBringRetries() {
        givenClaimedWithStatus(ProjectStatus.SUSPENDED);
        org.mockito.Mockito.doThrow(new IllegalStateException("disk full"))
                .when(workspaceService).init(TENANT, PROJECT);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.bring(TENANT, PROJECT))
                .isInstanceOf(IllegalStateException.class);

        org.assertj.core.api.Assertions
                .assertThat(registry.isActive(TENANT, PROJECT)).isFalse();
    }
}
