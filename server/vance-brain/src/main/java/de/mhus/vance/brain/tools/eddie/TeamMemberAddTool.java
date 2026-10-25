package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
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
 * Adds an existing account to a team — one atomic update, so concurrent
 * additions do not overwrite each other. The member gets the project roles
 * the team holds. Team ADMIN, which is tenant ADMIN: someone who already
 * administers every project, so the team gives no one more than the caller
 * could grant directly.
 */
@Component
@RequiredArgsConstructor
public class TeamMemberAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "team", Map.of("type", "string", "description", "Team name."),
                            "user", Map.of("type", "string", "description", "Username to add.")),
            "required", List.of("team", "user"));

    private final TeamService teamService;
    private final UserService userService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "team_member_add";
    }

    @Override
    public String description() {
        return "Add a user to a team. The user gets the project access the team has. Needs tenant ADMIN.";
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
        return "put a user into a team, join team";
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
        if (!userService.existsByTenantAndName(ctx.tenantId(), user)) {
            throw new ToolException("no account '" + user + "'");
        }
        boolean added;
        try {
            added = teamService.addMember(ctx.tenantId(), team, user);
        } catch (TeamService.TeamNotFoundException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (added) {
            admin.record(
                    ctx,
                    EddieActivityKind.TEAM_CHANGED,
                    "`" + user + "` zu Team `" + team + "` hinzugefügt",
                    List.of(EntityRef.team(team), EntityRef.user(user)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", team);
        out.put("user", user);
        out.put("added", added);
        if (!added) {
            out.put("note", "'" + user + "' already was a member.");
        }
        return out;
    }
}
