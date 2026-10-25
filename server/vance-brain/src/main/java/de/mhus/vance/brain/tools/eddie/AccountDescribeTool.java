package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One account in full: profile, status, sign-in, and the roles it holds —
 * the tenant role, its own project memberships, and the memberships it gets
 * through its teams. Tenant ADMIN, or the user asking about themselves.
 */
@Component
@RequiredArgsConstructor
public class AccountDescribeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                            Map.of(
                                    "type", "string",
                                    "description", "Username (use account_list to discover).")),
            "required", List.of("name"));

    private final UserService userService;
    private final TeamService teamService;
    private final RoleAssignmentService assignments;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "account_describe";
    }

    @Override
    public String description() {
        return "Describe one user account: title, email, status, sign-in method, lock state, tenant role, "
                + "project roles (own and via teams) and teams. Needs tenant ADMIN, except for your own account.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "details of a user, which roles does someone have, user account info";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, "read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext actor = admin.actor(ctx, name());
        String name = AdminToolSupport.req(params, "name");
        if (!name.equals(ctx.userId())) {
            admin.enforce(actor, new Resource.Tenant(ctx.tenantId()), Action.WRITE);
        }
        UserDocument user = userService
                .findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException("Account '" + name + "' not found"));

        Map<String, Object> out = AccountRows.summary(user, admin.tenantRoleOf(ctx.tenantId(), name));
        if (user.getEmail() != null) {
            out.put("email", user.getEmail());
        }
        out.put("loginEnabled", user.isLoginEnabled());
        if (userService.isLocked(user)) {
            out.put("lockedUntil", String.valueOf(user.getLockedUntil()));
        }
        if (user.getCreatedAt() != null) {
            out.put("createdAt", user.getCreatedAt().toString());
        }

        out.put("projectRoles", projectRoles(ctx.tenantId(), RoleSubjectType.USER, name));

        List<TeamDocument> teams = teamService.byMember(ctx.tenantId(), name).stream()
                .sorted(Comparator.comparing(TeamDocument::getName))
                .toList();
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (TeamDocument team : teams) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", team.getName());
            if (!team.isEnabled()) {
                row.put("enabled", false);
            }
            List<Map<String, Object>> viaTeam = projectRoles(ctx.tenantId(), RoleSubjectType.TEAM, team.getName());
            if (!viaTeam.isEmpty()) {
                row.put("projectRoles", viaTeam);
            }
            teamRows.add(row);
        }
        out.put("teams", teamRows);
        return out;
    }

    private List<Map<String, Object>> projectRoles(String tenantId, RoleSubjectType subjectType, String subjectId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        assignments.forSubject(tenantId, subjectType, subjectId).stream()
                .filter(a -> a.getScopeType() == RoleScopeType.PROJECT && a.getProjectRole() != null)
                .sorted(Comparator.comparing(RoleAssignmentDocument::getScopeId))
                .forEach(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("project", a.getScopeId());
                    row.put("role", a.getProjectRole().name());
                    rows.add(row);
                });
        return rows;
    }
}
