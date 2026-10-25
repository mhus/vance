package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectQuotaService.ProjectQuotaExceededException;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.role.RoleBootstrap;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The active-project quota is checked where a project becomes active —
 * {@code create} and {@code reopen} — and {@code reopen} brings the project
 * back into the group it was archived from.
 */
class ProjectLifecycleQuotaTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "kunde-x";

    private ProjectService projectService;
    private ProjectPlacementService placementService;
    private ProjectQuotaService quotaService;
    private ProjectGroupService groupService;
    private ProjectLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        placementService = mock(ProjectPlacementService.class);
        quotaService = mock(ProjectQuotaService.class);
        groupService = mock(ProjectGroupService.class);
        service = new ProjectLifecycleService(
                projectService,
                mock(ProjectManagerService.class),
                placementService,
                mock(WorkspaceService.class),
                mock(SessionService.class),
                mock(ApplicationEventPublisher.class),
                new ProjectActivationRegistry(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(RoleBootstrap.class),
                quotaService,
                groupService);
    }

    private static ProjectDocument project(ProjectStatus status, String group, String archivedFrom) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .projectGroupId(group)
                .archivedFromGroupId(archivedFrom)
                .build();
    }

    @Test
    void create_atQuota_createsNothing() {
        doThrow(new ProjectQuotaExceededException(5, 5)).when(quotaService).checkOneMore(TENANT);

        assertThatThrownBy(() -> service.create(TENANT, PROJECT, null, null, null, ProjectKind.NORMAL, "marvin"))
                .isInstanceOf(ProjectQuotaExceededException.class);
        verify(projectService, never()).create(anyString(), anyString(), any(), any(), anyList(), any());
    }

    @Test
    void create_systemProject_isNotCounted() {
        ProjectDocument hub =
                ProjectDocument.builder().tenantId(TENANT).name("_user_marvin").build();
        when(projectService.create(eq(TENANT), eq("_user_marvin"), any(), any(), any(), eq(ProjectKind.SYSTEM)))
                .thenReturn(hub);
        when(projectService.findByTenantAndName(TENANT, "_user_marvin")).thenReturn(Optional.of(hub));

        service.create(TENANT, "_user_marvin", null, null, null, ProjectKind.SYSTEM, null);

        verify(quotaService, never()).checkOneMore(anyString());
    }

    @Test
    void reopen_restoresTheOriginGroup_andPlaces() {
        ProjectDocument closed = project(ProjectStatus.CLOSED, "archived", "sales");
        ProjectDocument reopened = project(ProjectStatus.INIT, "sales", null);
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(closed))
                .thenReturn(Optional.of(reopened));
        when(groupService.existsByTenantAndName(TENANT, "sales")).thenReturn(true);
        when(projectService.reopen(TENANT, PROJECT, "sales")).thenReturn(reopened);

        ProjectDocument result = service.reopen(TENANT, PROJECT);

        assertThat(result.getProjectGroupId()).isEqualTo("sales");
        verify(quotaService).checkOneMore(TENANT);
        verify(placementService).place(reopened, PlacementTrigger.CREATE);
    }

    @Test
    void reopen_originGroupGone_landsUngrouped() {
        ProjectDocument closed = project(ProjectStatus.CLOSED, "archived", "sales");
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.of(closed));
        when(groupService.existsByTenantAndName(TENANT, "sales")).thenReturn(false);
        when(projectService.reopen(eq(TENANT), eq(PROJECT), isNull()))
                .thenReturn(project(ProjectStatus.INIT, null, null));

        service.reopen(TENANT, PROJECT);

        verify(projectService).reopen(eq(TENANT), eq(PROJECT), isNull());
    }

    @Test
    void reopen_atQuota_leavesTheProjectArchived() {
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(project(ProjectStatus.CLOSED, "archived", "sales")));
        doThrow(new ProjectQuotaExceededException(5, 5)).when(quotaService).checkOneMore(TENANT);

        assertThatThrownBy(() -> service.reopen(TENANT, PROJECT)).isInstanceOf(ProjectQuotaExceededException.class);
        verify(projectService, never()).reopen(anyString(), anyString(), any());
    }

    @Test
    void reopen_ofAProjectThatIsNotArchived_isAConflict_withoutTouchingTheQuota() {
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(project(ProjectStatus.SUSPENDED, "sales", null)));

        assertThatThrownBy(() -> service.reopen(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);
        verify(quotaService, never()).checkOneMore(anyString());
    }
}
