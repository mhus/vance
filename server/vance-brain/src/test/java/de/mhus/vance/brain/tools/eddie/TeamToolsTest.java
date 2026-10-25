package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Team tools: quota on create and on enable, only existing accounts as
 * members, members changed one at a time, and a repeated name before a
 * delete takes the team's project access away.
 */
class TeamToolsTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "_user_alice", "s", "p", "alice");
    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of());

    private TeamService teamService;
    private TeamQuotaService quotaService;
    private UserService userService;
    private AdminToolSupport admin;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        quotaService = mock(TeamQuotaService.class);
        userService = mock(UserService.class);
        admin = mock(AdminToolSupport.class);
        when(admin.actor(any(), anyString())).thenReturn(ALICE);
    }

    private static TeamDocument team(String name, boolean enabled) {
        return TeamDocument.builder()
                .tenantId("acme")
                .name(name)
                .enabled(enabled)
                .build();
    }

    @Test
    void create_withExistingMembers() {
        TeamCreateTool tool = new TeamCreateTool(teamService, quotaService, userService, admin);
        when(userService.existsByTenantAndName("acme", "bob")).thenReturn(true);
        when(teamService.create("acme", "infra", null, List.of("bob"))).thenReturn(team("infra", true));

        tool.invoke(Map.of("name", "infra", "members", List.of("bob", "bob")), CTX);

        verify(admin).enforce(ALICE, new Resource.Tenant("acme"), Action.WRITE);
        verify(quotaService).checkOneMore("acme");
        verify(teamService).create("acme", "infra", null, List.of("bob"));
    }

    @Test
    void create_unknownMemberCreatesNothing() {
        TeamCreateTool tool = new TeamCreateTool(teamService, quotaService, userService, admin);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "infra", "members", List.of("ghost")), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ghost");
        verify(teamService, never()).create(any(), any(), any(), any());
    }

    @Test
    void create_atTheTeamLimitCreatesNothing() {
        TeamCreateTool tool = new TeamCreateTool(teamService, quotaService, userService, admin);
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quotaService)
                .checkOneMore("acme");

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "infra"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("3 of 3");
        verify(teamService, never()).create(any(), any(), any(), any());
    }

    @Test
    void update_enablingChecksTheQuota() {
        TeamUpdateTool tool = new TeamUpdateTool(teamService, quotaService, admin);
        TeamDocument off = team("infra", false);
        when(teamService.findByTenantAndName("acme", "infra")).thenReturn(Optional.of(off));
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quotaService)
                .checkEnabledChange("acme", off, true);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "infra", "enabled", true), CTX))
                .isInstanceOf(ToolException.class);
        verify(teamService, never()).update(any(), any(), any(), any(), any());
    }

    @Test
    void update_neverReplacesTheMemberList() {
        TeamUpdateTool tool = new TeamUpdateTool(teamService, quotaService, admin);
        when(teamService.findByTenantAndName("acme", "infra")).thenReturn(Optional.of(team("infra", true)));
        when(teamService.update("acme", "infra", "Infra", null, null)).thenReturn(team("infra", true));

        tool.invoke(Map.of("name", "infra", "title", "Infra"), CTX);

        verify(admin).enforce(ALICE, new Resource.Team("acme", "infra"), Action.ADMIN);
        verify(teamService).update("acme", "infra", "Infra", null, null);
    }

    @Test
    void delete_needsTheNameTwice() {
        TeamDeleteTool tool = new TeamDeleteTool(teamService, mock(RoleAssignmentService.class), admin);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "infra", "confirmName", "infro"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("does not match");
        verify(teamService, never()).delete(any(), any());
    }

    @Test
    void delete_reportsTheMembershipsThatGo() {
        RoleAssignmentService assignments = mock(RoleAssignmentService.class);
        TeamDeleteTool tool = new TeamDeleteTool(teamService, assignments, admin);
        when(teamService.existsByTenantAndName("acme", "infra")).thenReturn(true);
        RoleAssignmentDocument a = new RoleAssignmentDocument();
        a.setScopeType(RoleScopeType.PROJECT);
        a.setScopeId("ops");
        a.setSubjectType(RoleSubjectType.TEAM);
        a.setSubjectId("infra");
        a.setProjectRole(ProjectRole.MEMBER);
        when(assignments.forSubject("acme", RoleSubjectType.TEAM, "infra")).thenReturn(List.of(a));

        Map<String, Object> out = tool.invoke(Map.of("name", "infra", "confirmName", "infra"), CTX);

        verify(teamService).delete("acme", "infra");
        assertThat(out).containsEntry("removedProjectMemberships", List.of("ops"));
    }

    @Test
    void memberAdd_onlyAnExistingAccount() {
        TeamMemberAddTool tool = new TeamMemberAddTool(teamService, userService, admin);

        assertThatThrownBy(() -> tool.invoke(Map.of("team", "infra", "user", "ghost"), CTX))
                .isInstanceOf(ToolException.class);
        verify(teamService, never()).addMember(any(), any(), any());
    }

    @Test
    void memberAdd_isAtomicAndIdempotent() {
        TeamMemberAddTool tool = new TeamMemberAddTool(teamService, userService, admin);
        when(userService.existsByTenantAndName("acme", "bob")).thenReturn(true);
        when(teamService.addMember("acme", "infra", "bob")).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of("team", "infra", "user", "bob"), CTX);

        verify(admin).enforce(ALICE, new Resource.Team("acme", "infra"), Action.ADMIN);
        assertThat(out).containsEntry("added", false).containsKey("note");
        verify(admin, never()).record(any(), any(), anyString(), any());
    }

    @Test
    void memberRemove_unknownTeamIsAnError() {
        TeamMemberRemoveTool tool = new TeamMemberRemoveTool(teamService, admin);
        when(teamService.removeMember("acme", "ghost", "bob"))
                .thenThrow(new TeamService.TeamNotFoundException("Team 'ghost' not found"));

        assertThatThrownBy(() -> tool.invoke(Map.of("team", "ghost", "user", "bob"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ghost");
    }
}
