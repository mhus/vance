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
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.roles.RoleChangeSupport;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserQuotaKind;
import de.mhus.vance.shared.user.UserQuotaService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The account tools mirror the admin user list: tenant ADMIN to list and
 * create, User ADMIN to change, the seat quota on create and re-enable, no
 * password through the chat, never disabling oneself.
 */
class AccountToolsTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "_user_alice", "s", "p", "alice");
    private static final SecurityContext ALICE = SecurityContext.user("alice", "acme", List.of());

    private UserService userService;
    private UserQuotaService quotaService;
    private AdminToolSupport admin;
    private RoleChangeSupport roleChanges;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        quotaService = mock(UserQuotaService.class);
        admin = mock(AdminToolSupport.class);
        roleChanges = mock(RoleChangeSupport.class);
        when(admin.actor(any(), anyString())).thenReturn(ALICE);
        when(admin.tenantRoleOf(anyString(), anyString())).thenReturn(TenantRole.USER);
    }

    private static UserDocument user(String name, UserStatus status) {
        UserDocument u = new UserDocument();
        u.setTenantId("acme");
        u.setName(name);
        u.setStatus(status);
        return u;
    }

    // ─── account_create ────────────────────────────────────────────────────

    private AccountCreateTool createTool() {
        return new AccountCreateTool(userService, quotaService, admin, roleChanges);
    }

    @Test
    void create_withoutPasswordAndWithRoles() {
        when(userService.create("acme", "bob", null, "Bob", null)).thenReturn(user("bob", UserStatus.ACTIVE));
        when(roleChanges.grant(any(), anyString(), any(), any())).thenReturn(Map.of("applied", true));

        Map<String, Object> out = createTool()
                .invoke(
                        Map.of(
                                "name", "bob",
                                "title", "Bob",
                                "tenantRole", "creator",
                                "projectRoles", List.of(Map.of("project", "research", "role", "MEMBER"))),
                        CTX);

        verify(admin).enforce(ALICE, new Resource.Tenant("acme"), Action.WRITE);
        verify(quotaService).checkOneMore("acme", UserQuotaKind.SEATS);
        verify(roleChanges)
                .grant(
                        CTX,
                        "account_create",
                        RoleRequestService.Target.tenantGrant("acme", "bob", TenantRole.CREATOR),
                        "Requested while creating the account.");
        verify(roleChanges)
                .grant(
                        CTX,
                        "account_create",
                        RoleRequestService.Target.projectGrant(
                                "research", RoleSubjectType.USER, "bob", ProjectRole.MEMBER),
                        "Requested while creating the account.");
        assertThat(out).containsKey("signInNote");
        assertThat((List<?>) out.get("roles")).hasSize(2);
    }

    @Test
    void create_refusedRoleIsReportedNotThrown() {
        when(userService.create(any(), any(), any(), any(), any())).thenReturn(user("bob", UserStatus.ACTIVE));
        when(roleChanges.grant(any(), anyString(), any(), any())).thenThrow(new ToolException("no project 'x'"));

        Map<String, Object> out = createTool()
                .invoke(Map.of("name", "bob", "projectRoles", List.of(Map.of("project", "x", "role", "READER"))), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) out.get("roles");
        assertThat(roles.get(0)).containsEntry("applied", false).containsEntry("error", "no project 'x'");
    }

    @Test
    void create_badRoleIsCaughtBeforeTheAccountExists() {
        assertThatThrownBy(() -> createTool().invoke(Map.of("name", "bob", "tenantRole", "OWNER"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenantRole");
        verify(userService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void create_atTheSeatLimitCreatesNothing() {
        doThrow(new UserQuotaService.UserQuotaExceededException(UserQuotaKind.SEATS, 5, 5))
                .when(quotaService)
                .checkOneMore("acme", UserQuotaKind.SEATS);

        assertThatThrownBy(() -> createTool().invoke(Map.of("name", "bob"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("5 of 5");
        verify(userService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void create_refusesServiceAccounts() {
        assertThatThrownBy(() -> createTool().invoke(Map.of("name", "_bot"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("service account");
        verify(userService, never()).create(any(), any(), any(), any(), any());
    }

    // ─── account_disable / account_enable ──────────────────────────────────

    @Test
    void disable_neverOneself() {
        AccountDisableTool tool = new AccountDisableTool(userService, admin);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "alice"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("your own account");
        verify(userService, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void disable_needsUserAdmin() {
        AccountDisableTool tool = new AccountDisableTool(userService, admin);
        when(userService.findByTenantAndName("acme", "bob")).thenReturn(Optional.of(user("bob", UserStatus.ACTIVE)));
        when(userService.update("acme", "bob", null, null, UserStatus.DISABLED, null))
                .thenReturn(user("bob", UserStatus.DISABLED));

        Map<String, Object> out = tool.invoke(Map.of("name", "bob"), CTX);

        verify(admin).enforce(ALICE, new Resource.User("acme", "bob"), Action.ADMIN);
        assertThat(out).containsEntry("status", "DISABLED");
    }

    @Test
    void enable_checksTheQuota() {
        AccountEnableTool tool = new AccountEnableTool(userService, quotaService, admin);
        UserDocument bob = user("bob", UserStatus.DISABLED);
        when(userService.findByTenantAndName("acme", "bob")).thenReturn(Optional.of(bob));
        doThrow(new UserQuotaService.UserQuotaExceededException(UserQuotaKind.SEATS, 5, 5))
                .when(quotaService)
                .checkStatusChange("acme", bob, UserStatus.ACTIVE);

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "bob"), CTX)).isInstanceOf(ToolException.class);
        verify(userService, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void enable_onlyADisabledAccount() {
        AccountEnableTool tool = new AccountEnableTool(userService, quotaService, admin);
        when(userService.findByTenantAndName("acme", "bob")).thenReturn(Optional.of(user("bob", UserStatus.ACTIVE)));

        assertThatThrownBy(() -> tool.invoke(Map.of("name", "bob"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not disabled");
    }

    // ─── account_describe ──────────────────────────────────────────────────

    @Test
    void describe_showsTenantRoleProjectRolesAndTeamRoles() {
        TeamService teamService = mock(TeamService.class);
        RoleAssignmentService assignments = mock(RoleAssignmentService.class);
        AccountDescribeTool tool = new AccountDescribeTool(userService, teamService, assignments, admin);
        when(userService.findByTenantAndName("acme", "bob")).thenReturn(Optional.of(user("bob", UserStatus.ACTIVE)));
        when(admin.tenantRoleOf("acme", "bob")).thenReturn(TenantRole.CREATOR);
        when(assignments.forSubject("acme", RoleSubjectType.USER, "bob"))
                .thenReturn(List.of(membership("research", RoleSubjectType.USER, "bob", ProjectRole.ADMIN)));
        TeamDocument infra = TeamDocument.builder()
                .tenantId("acme")
                .name("infra")
                .enabled(true)
                .build();
        when(teamService.byMember("acme", "bob")).thenReturn(List.of(infra));
        when(assignments.forSubject("acme", RoleSubjectType.TEAM, "infra"))
                .thenReturn(List.of(membership("ops", RoleSubjectType.TEAM, "infra", ProjectRole.MEMBER)));

        Map<String, Object> out = tool.invoke(Map.of("name", "bob"), CTX);

        verify(admin).enforce(ALICE, new Resource.Tenant("acme"), Action.WRITE);
        assertThat(out).containsEntry("tenantRole", "CREATOR");
        assertThat(out.get("projectRoles")).isEqualTo(List.of(Map.of("project", "research", "role", "ADMIN")));
        assertThat(out.get("teams"))
                .isEqualTo(List.of(
                        Map.of("name", "infra", "projectRoles", List.of(Map.of("project", "ops", "role", "MEMBER")))));
        assertThat(out).doesNotContainKey("passwordHash");
    }

    @Test
    void describe_ownAccountNeedsNoTenantAdmin() {
        AccountDescribeTool tool =
                new AccountDescribeTool(userService, mock(TeamService.class), mock(RoleAssignmentService.class), admin);
        when(userService.findByTenantAndName("acme", "alice"))
                .thenReturn(Optional.of(user("alice", UserStatus.ACTIVE)));

        tool.invoke(Map.of("name", "alice"), CTX);

        verify(admin, never()).enforce(any(), any(), any());
    }

    private static RoleAssignmentDocument membership(
            String project, RoleSubjectType subjectType, String subjectId, ProjectRole role) {
        RoleAssignmentDocument a = new RoleAssignmentDocument();
        a.setTenantId("acme");
        a.setScopeType(RoleScopeType.PROJECT);
        a.setScopeId(project);
        a.setSubjectType(subjectType);
        a.setSubjectId(subjectId);
        a.setProjectRole(role);
        return a;
    }
}
