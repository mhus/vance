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
 * The active-spot marking of {@code project_list}: the caller's
 * working-project spot (Eddie's current focus) must be visible in the same
 * call — the matching row carries {@code active: true} and the top-level
 * {@code activeProject} names it. Without it a hub LLM needs a separate
 * {@code project_current} round-trip to learn where it stands.
 */
class ProjectListToolActiveSpotTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final ProjectListTool tool = new ProjectListTool(projectService, contextFactory);

    @Test
    void marksTheSpotRow_andNamesItTopLevel() {
        givenProjects(project("alpha"), project("beta"));

        Map<String, Object> out = tool.invoke(Map.of(), ctxWithSpot("beta"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        Map<String, Object> alpha = rowByName(rows, "alpha");
        Map<String, Object> beta = rowByName(rows, "beta");
        assertThat(alpha).doesNotContainKey("active");
        assertThat(beta).containsEntry("active", true);
        assertThat(out).containsEntry("activeProject", "beta");
    }

    @Test
    void noSpot_noMarking() {
        givenProjects(project("alpha"));

        Map<String, Object> out =
                tool.invoke(Map.of(), new ToolInvocationContext("acme", "alpha", "sess", "proc", "alice"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        assertThat(rows.get(0)).doesNotContainKey("active");
        assertThat(out).doesNotContainKey("activeProject");
    }

    @Test
    void spotOutsideTheFilteredView_isStillNamedTopLevel() {
        // Spot = own SYSTEM hub while includeSystem=false hides the row —
        // the top-level field keeps the LLM's focus visible anyway.
        givenProjects(project("alpha"));

        Map<String, Object> out = tool.invoke(Map.of(), ctxWithSpot("_user_alice"));

        assertThat(out).containsEntry("activeProject", "_user_alice");
        assertThat(out).doesNotContainKey("active");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("projects");
        assertThat(rows.get(0)).doesNotContainKey("active");
    }

    @SuppressWarnings("unchecked")
    private void givenProjects(ProjectDocument... projects) {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        when(projectService.listReadableBy(eq("acme"), eq(subject))).thenReturn(List.of(projects));
    }

    private static ToolInvocationContext ctxWithSpot(String spot) {
        return new ToolInvocationContext("acme", "_user_alice", "sess", "proc", "alice", spot);
    }

    private static Map<String, Object> rowByName(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow();
    }

    private static ProjectDocument project(String name) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        return p;
    }
}
