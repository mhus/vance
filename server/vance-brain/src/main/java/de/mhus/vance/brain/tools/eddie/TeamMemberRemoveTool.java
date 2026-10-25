package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
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
 * Removes a user from a team — one atomic update. The user loses the project
 * access the team gave (their own memberships stay). Team ADMIN.
 */
@Component
@RequiredArgsConstructor
public class TeamMemberRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "team", Map.of("type", "string", "description", "Team name."),
                            "user", Map.of("type", "string", "description", "Username to remove.")),
            "required", List.of("team", "user"));

    private final TeamService teamService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "team_member_remove";
    }

    @Override
    public String description() {
        return "Remove a user from a team. They lose the project access the team gave them; their own "
                + "memberships stay. Needs tenant ADMIN.";
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
        return "take a user out of a team, leave team";
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
        String team = AdminToolSupport.req(params, "team");
        String user = AdminToolSupport.req(params, "user");
        admin.enforce(actor, new Resource.Team(ctx.tenantId(), team), Action.ADMIN);
        boolean removed;
        try {
            removed = teamService.removeMember(ctx.tenantId(), team, user);
        } catch (TeamService.TeamNotFoundException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (removed) {
            admin.record(
                    ctx,
                    EddieActivityKind.TEAM_CHANGED,
                    "`" + user + "` aus Team `" + team + "` entfernt",
                    List.of(EntityRef.team(team), EntityRef.user(user)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", team);
        out.put("user", user);
        out.put("removed", removed);
        if (!removed) {
            out.put("note", "'" + user + "' was not a member.");
        }
        return out;
    }
}
