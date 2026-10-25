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
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The archive tool discards a workspace for good, so the fences matter more
 * than the happy path: repeated name, never the own or a SYSTEM project, no
 * pinned process, project ADMIN before anything is touched.
 */
class ProjectArchiveToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "_user_alice", "s", "p", "alice");

    private ProjectLifecycleService lifecycle;
    private ProjectService projectService;
    private EddieContext eddieContext;
    private PermissionService permissionService;
    private ProjectArchiveTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lifecycle = mock(ProjectLifecycleService.class);
        ObjectProvider<ProjectLifecycleService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(lifecycle);
        projectService = mock(ProjectService.class);
        ProjectGroupService groupService = mock(ProjectGroupService.class);
        ProjectGroupDocument archived = new ProjectGroupDocument();
        archived.setName(ProjectGroupService.ARCHIVED_GROUP_NAME);
        when(groupService.ensureArchivedGroup("acme")).thenReturn(archived);
        eddieContext = mock(EddieContext.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(anyString(), any())).thenReturn(mock(SecurityContext.class));
        tool = new ProjectArchiveTool(
                provider,
                projectService,
                groupService,
                eddieContext,
                mock(EddieActivityService.class),
                permissionService,
                contextFactory);
    }

    private ProjectDocument arrange(String name, ProjectKind kind, ProjectStatus status) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        p.setTenantId("acme");
        p.setKind(kind);
        p.setStatus(status);
        when(projectService.findByTenantAndName("acme", name)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void archivesIntoTheArchivedGroup() {
        arrange("research", ProjectKind.NORMAL, ProjectStatus.RUNNING);
        ProjectDocument closed = new ProjectDocument();
        closed.setName("research");
        closed.setStatus(ProjectStatus.CLOSED);
        closed.setProjectGroupId("archived");
        closed.setArchivedFromGroupId("team-a");
        when(lifecycle.close("acme", "research", "archived")).thenReturn(closed);

        Map<String, Object> out = tool.invoke(Map.of("name", "research", "confirmName", "research"), CTX);

        verify(permissionService).enforce(any(), eq(new Resource.Project("acme", "research")), eq(Action.ADMIN));
        assertThat(out)
                .containsEntry("status", "CLOSED")
                .containsEntry("projectGroupId", "archived")
                .containsEntry("archivedFromGroupId", "team-a")
                .containsEntry("workspaceDiscarded", true);
    }

    @Test
    void refusesAMismatchedConfirmName() {
        arrange("research", ProjectKind.NORMAL, ProjectStatus.RUNNING);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research", "confirmName", "reserch"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("does not match");
        verify(lifecycle, never()).close(anyString(), anyString(), anyString());
    }

    @Test
    void refusesTheOwnProject() {
        ToolInvocationContext inProject = new ToolInvocationContext("acme", "research", "s", "p", "alice");

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research", "confirmName", "research"), inProject))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot archive itself");
        verify(lifecycle, never()).close(anyString(), anyString(), anyString());
    }

    @Test
    void refusesASystemProject() {
        arrange("_user_bob", ProjectKind.SYSTEM, ProjectStatus.RUNNING);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "_user_bob", "confirmName", "_user_bob"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("system project");
        verify(lifecycle, never()).close(anyString(), anyString(), anyString());
    }

    @Test
    void refusesFromAPinnedProcess() {
        when(eddieContext.isPinnedToOwnProject(CTX)).thenReturn(true);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research", "confirmName", "research"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not allowed in this process");
    }

    @Test
    void refusesAnAlreadyArchivedProject() {
        arrange("research", ProjectKind.NORMAL, ProjectStatus.CLOSED);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research", "confirmName", "research"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already archived");
    }

    @Test
    void permissionDenialTouchesNothing() {
        arrange("research", ProjectKind.NORMAL, ProjectStatus.RUNNING);
        doThrow(new IllegalStateException("denied")).when(permissionService).enforce(any(), any(), eq(Action.ADMIN));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "research", "confirmName", "research"), CTX))
                .hasMessageContaining("denied");
        verify(lifecycle, never()).close(anyString(), anyString(), anyString());
    }
}
