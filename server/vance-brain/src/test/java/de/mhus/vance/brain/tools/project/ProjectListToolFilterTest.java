package de.mhus.vance.brain.tools.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * project_list must only surface projects the caller may READ — otherwise
 * the LLM sees projects it is denied on when it tries to use them
 * (permission-system finding #11).
 */
class ProjectListToolFilterTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final ProjectListTool tool =
            new ProjectListTool(projectService, permissionService, contextFactory);

    @Test
    void lists_only_projects_the_subject_may_read() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        when(projectService.all("acme")).thenReturn(List.of(project("mine"), project("secret")));
        when(permissionService.check(eq(subject),
                eq(new Resource.Project("acme", "mine")), eq(Action.READ))).thenReturn(true);
        when(permissionService.check(eq(subject),
                eq(new Resource.Project("acme", "secret")), eq(Action.READ))).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of(),
                new ToolInvocationContext("acme", "mine", "sess", "proc", "alice"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("mine");
    }

    private static ProjectDocument project(String name) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        return p;
    }
}
