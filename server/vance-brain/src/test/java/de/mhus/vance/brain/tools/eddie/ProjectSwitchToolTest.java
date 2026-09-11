package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The switch gate mirrors {@code EddieContext.resolveProject}: the own
 * hub and the {@code _tenant} system project are legitimate spots,
 * everything else SYSTEM — other users' hubs above all — is not
 * reachable through an LLM tool call.
 */
class ProjectSwitchToolTest {

    private ProjectService projectService;
    private EddieContext eddieContext;
    private ProjectSwitchTool tool;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        eddieContext = mock(EddieContext.class);
        tool = new ProjectSwitchTool(projectService, eddieContext, mock(EddieActivityService.class));
    }

    private ProjectDocument arrange(String name, ProjectKind kind) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        p.setTenantId("acme");
        p.setKind(kind);
        when(projectService.findByTenantAndName("acme", name)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void switchesToTheTenantProject() {
        arrange("_tenant", ProjectKind.SYSTEM);
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "work", "s", "p", "alice");

        Map<String, Object> out = tool.invoke(Map.of("name", "_tenant"), ctx);

        assertThat(out.get("name")).isEqualTo("_tenant");
        verify(eddieContext).writeActiveProject(ctx, "_tenant");
    }

    @Test
    void switchesToTheOwnHub() {
        arrange("_user_alice", ProjectKind.SYSTEM);
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "work", "s", "p", "alice");

        Map<String, Object> out = tool.invoke(Map.of("name", "_user_alice"), ctx);

        assertThat(out.get("name")).isEqualTo("_user_alice");
        verify(eddieContext).writeActiveProject(ctx, "_user_alice");
    }

    @Test
    void refusesAnotherUsersHub() {
        arrange("_user_bob", ProjectKind.SYSTEM);
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "work", "s", "p", "alice");

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "_user_bob"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("another user's hub");
    }
}
