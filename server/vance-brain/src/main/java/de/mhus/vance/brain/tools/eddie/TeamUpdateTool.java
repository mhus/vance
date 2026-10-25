package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Changes a team's title and enabled flag. Members change one at a time
 * through {@code team_member_add}/{@code team_member_remove} — never as a
 * whole list, which would drop a member someone added in between. Enabling a
 * disabled team is checked against the team quota. Team ADMIN.
 */
@Component
@RequiredArgsConstructor
public class TeamUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name", Map.of("type", "string", "description", "Team name."),
                            "title", Map.of("type", "string", "description", "New display name."),
                            "enabled",
                                    Map.of(
                                            "type",
                                            "boolean",
                                            "description",
                                            "false disables the team (its project roles stop applying); "
                                                    + "true enables it again.")),
            "required", List.of("name"));

    private final TeamService teamService;
    private final TeamQuotaService quotaService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "team_update";
    }

    @Override
    public String description() {
        return "Change a team's display name (title) or enable/disable it. Needs tenant ADMIN. Members: "
                + "team_member_add / team_member_remove.";
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
        return "rename a team, disable or enable a team";
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
        String title = AdminToolSupport.str(params, "title");
        Boolean enabled = AdminToolSupport.bool(params, "enabled");
        if (title == null && enabled == null) {
            throw new ToolException("nothing to change — pass 'title' and/or 'enabled'");
        }
        admin.enforce(actor, new Resource.Team(ctx.tenantId(), name), Action.ADMIN);
        TeamDocument current = teamService
                .findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException("Team '" + name + "' not found"));
        if (Boolean.TRUE.equals(enabled)) {
            try {
                quotaService.checkEnabledChange(ctx.tenantId(), current, true);
            } catch (TeamQuotaService.TeamQuotaExceededException e) {
                throw new ToolException(e.getMessage(), e);
            }
        }
        TeamDocument saved;
        try {
            saved = teamService.update(ctx.tenantId(), name, title, enabled, null);
        } catch (TeamService.TeamNotFoundException e) {
            throw new ToolException(e.getMessage(), e);
        }
        admin.record(ctx, EddieActivityKind.TEAM_CHANGED, "Changed team `" + name + "`", List.of(EntityRef.team(name)));
        return TeamCreateTool.row(saved);
    }
}
