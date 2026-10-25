package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes a team. Its project memberships go with it (the team lifecycle
 * listener removes them first), so every member loses the access the team
 * gave. That cannot be undone by re-creating the team, hence the repeated
 * name — as with {@code project_archive}. Disabling the team
 * ({@code team_update enabled=false}) is the reversible alternative.
 */
@Component
@RequiredArgsConstructor
public class TeamDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name", Map.of("type", "string", "description", "Name of the team to delete."),
                            "confirmName",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "The same team name again. Only set it after the user has "
                                                    + "confirmed deleting this team.")),
            "required", List.of("name", "confirmName"));

    private final TeamService teamService;
    private final RoleAssignmentService assignments;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "team_delete";
    }

    @Override
    public String description() {
        return "Delete a team. Its project memberships are removed too — members lose the access the team "
                + "gave them. Ask the user first, then pass the name twice. team_update(enabled=false) is "
                + "the reversible alternative. Needs tenant ADMIN.";
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
        return "remove a team, delete a group of users";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, ToolLabels.EXECUTIVE, ToolLabels.WRITE);
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext actor = admin.actor(ctx, name());
        String name = AdminToolSupport.req(params, "name");
        AdminToolSupport.requireConfirmed(name, AdminToolSupport.req(params, "confirmName"), "team");
        admin.enforce(actor, new Resource.Team(ctx.tenantId(), name), Action.ADMIN);
        if (!teamService.existsByTenantAndName(ctx.tenantId(), name)) {
            throw new ToolException("Team '" + name + "' not found");
        }
        List<String> projects = assignments.forSubject(ctx.tenantId(), RoleSubjectType.TEAM, name).stream()
                .filter(a -> a.getScopeType() == RoleScopeType.PROJECT)
                .map(a -> a.getScopeId())
                .sorted()
                .toList();
        try {
            teamService.delete(ctx.tenantId(), name);
        } catch (TeamService.TeamNotFoundException e) {
            throw new ToolException(e.getMessage(), e);
        }
        admin.record(ctx, EddieActivityKind.TEAM_DELETED, "Deleted team `" + name + "`", List.of(EntityRef.team(name)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("deleted", true);
        out.put("removedProjectMemberships", projects);
        return out;
    }
}
