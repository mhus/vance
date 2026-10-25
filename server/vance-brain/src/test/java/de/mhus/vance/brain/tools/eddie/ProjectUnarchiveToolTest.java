package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectQuotaService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ProjectUnarchiveToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "_user_alice", "s", "p", "alice");

    private ProjectLifecycleService lifecycle;
    private ProjectService projectService;
    private PermissionService permissionService;
    private EddieContext eddieContext;
    private ProjectUnarchiveTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lifecycle = mock(ProjectLifecycleService.class);
        ObjectProvider<ProjectLifecycleService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(lifecycle);
        projectService = mock(ProjectService.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(anyString(), any())).thenReturn(mock(SecurityContext.class));
        eddieContext = mock(EddieContext.class);
        AdminToolSupport admin =
                new AdminToolSupport(eddieContext, mock(EddieActivityService.class), permissionService, contextFactory);
        tool = new ProjectUnarchiveTool(provider, projectService, admin);
        ProjectDocument archived = new ProjectDocument();
        archived.setName("research");
        archived.setStatus(ProjectStatus.CLOSED);
        when(projectService.findByTenantAndName("acme", "research")).thenReturn(Optional.of(archived));
    }

    @Test
    void reopensThroughTheLifecycle() {
        ProjectDocument reopened = new ProjectDocument();
        reopened.setName("research");
        reopened.setStatus(ProjectStatus.INIT);
        reopened.setProjectGroupId("team-a");
        when(lifecycle.reopen("acme", "research")).thenReturn(reopened);

        Map<String, Object> out = tool.invoke(Map.of("name", "research"), CTX);

        verify(permissionService).enforce(any(), eq(new Resource.Project("acme", "research")), eq(Action.ADMIN));
        assertThat(out)
                .containsEntry("status", "INIT")
                .containsEntry("projectGroupId", "team-a")
                .containsEntry("workspaceEmpty", true);
    }

    @Test
    void quotaRefusalBecomesAToolError() {
        when(lifecycle.reopen("acme", "research"))
                .thenThrow(new ProjectQuotaService.ProjectQuotaExceededException(5, 5));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("quota");
    }

    @Test
    void notArchivedBecomesAToolError() {
        when(lifecycle.reopen("acme", "research"))
                .thenThrow(new ProjectService.ProjectStatusConflictException("is RUNNING"));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void unknownProject() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "nope"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void permissionDenialTouchesNothing() {
        doThrow(new IllegalStateException("denied")).when(permissionService).enforce(any(), any(), eq(Action.ADMIN));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research"), CTX)).hasMessageContaining("denied");
        verify(lifecycle, never()).reopen(anyString(), anyString());
    }

    @Test
    void permissionIsCheckedBeforeTheLookup_soDenialRevealsNothing() {
        doThrow(new IllegalStateException("denied")).when(permissionService).enforce(any(), any(), eq(Action.ADMIN));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "ghost"), CTX)).hasMessageContaining("denied");
        verify(projectService, never()).findByTenantAndName(anyString(), anyString());
    }

    @Test
    void refusesFromAPinnedProcess() {
        when(eddieContext.isPinnedToOwnProject(CTX)).thenReturn(true);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not allowed in this process");
        verify(lifecycle, never()).reopen(anyString(), anyString());
    }
}
