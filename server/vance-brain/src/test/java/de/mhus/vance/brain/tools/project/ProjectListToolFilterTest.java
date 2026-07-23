package de.mhus.vance.brain.tools.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * project_list surfaces only what the authorized source returns — the
 * READ check lives in ProjectService.listReadableBy, the tool just passes
 * the caller identity (permission-system finding #11).
 */
class ProjectListToolFilterTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final ProjectListTool tool = new ProjectListTool(projectService, contextFactory);

    @Test
    void lists_only_what_the_authorized_source_returns() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        // Source already filtered by READ — the tool must not re-list all().
        when(projectService.listReadableBy(eq("acme"), eq(subject)))
                .thenReturn(List.of(project("mine")));

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
