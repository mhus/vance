package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * team_list tenant-wide listing (projectId="all") is admin-territory: a
 * non-admin caller sees only their own teams, not the whole tenant roster
 * (permission-system finding — team_list leak).
 */
class TeamListToolScopeTest {

    private final EddieContext eddieContext = mock(EddieContext.class);
    private final TeamService teamService = mock(TeamService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final TeamListTool tool =
            new TeamListTool(eddieContext, teamService, permissionService, contextFactory);

    @Test
    void tenantWide_nonAdmin_sees_only_own_teams() {
        SecurityContext bob = SecurityContext.user("bob", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "bob")).thenReturn(bob);
        when(teamService.all("acme")).thenReturn(List.of(team("rd"), team("qa"), team("ops")));
        when(permissionService.check(eq(bob),
                eq(new Resource.Tenant("acme")), eq(Action.ADMIN))).thenReturn(false);
        when(teamService.byMember("acme", "bob")).thenReturn(List.of(team("rd")));

        Map<String, Object> out = tool.invoke(Map.of("projectId", "all"),
                new ToolInvocationContext("acme", "p", "s", "proc", "bob"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("teams");
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("rd");
    }

    @Test
    void tenantWide_admin_sees_all_teams() {
        SecurityContext admin = SecurityContext.user("root", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "root")).thenReturn(admin);
        when(teamService.all("acme")).thenReturn(List.of(team("rd"), team("qa")));
        when(permissionService.check(eq(admin),
                eq(new Resource.Tenant("acme")), eq(Action.ADMIN))).thenReturn(true);

        Map<String, Object> out = tool.invoke(Map.of("projectId", "all"),
                new ToolInvocationContext("acme", "p", "s", "proc", "root"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("teams");
        assertThat(rows).extracting(r -> r.get("name")).containsExactlyInAnyOrder("rd", "qa");
    }

    private static TeamDocument team(String name) {
        TeamDocument t = new TeamDocument();
        t.setName(name);
        return t;
    }
}
