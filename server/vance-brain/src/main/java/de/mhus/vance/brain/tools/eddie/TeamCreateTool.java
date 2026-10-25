package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates a team, optionally with its first members — tenant ADMIN and the
 * tenant's team quota, as the admin team list. Every member must be an
 * existing account; a team holds no project role until one is granted
 * ({@code role_grant} with {@code subjectType=TEAM}).
 */
@Component
@RequiredArgsConstructor
public class TeamCreateTool implements Tool {

    /** Same rule as {@code TeamCreateRequest.name}. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*$");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Team name: lower-case letters, digits, '-', '_'."),
                            "title", Map.of("type", "string", "description", "Display name."),
                            "members",
                                    Map.of(
                                            "type",
                                            "array",
                                            "items",
                                            Map.of("type", "string"),
                                            "description",
                                            "Usernames of the first members (optional).")),
            "required", List.of("name"));

    private final TeamService teamService;
    private final TeamQuotaService quotaService;
    private final UserService userService;
    private final AdminToolSupport admin;

    @Override
    public String name() {
        return "team_create";
    }

    @Override
    public String description() {
        return "Create a team, optionally with members (existing usernames). Needs tenant ADMIN and room in "
                + "the team quota. Give it project access afterwards with role_grant (subjectType=TEAM).";
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
        return "new team, create a group of users";
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
        if (!NAME.matcher(name).matches()) {
            throw new ToolException("invalid team name '" + name + "' — lower-case letters, digits, '-' or '_'");
        }
        List<String> members = List.copyOf(new LinkedHashSet<>(AdminToolSupport.strings(params, "members")));
        admin.enforce(actor, new Resource.Tenant(ctx.tenantId()), Action.WRITE);
        for (String member : members) {
            if (!userService.existsByTenantAndName(ctx.tenantId(), member)) {
                throw new ToolException("no account '" + member + "' — nothing created");
            }
        }
        try {
            quotaService.checkOneMore(ctx.tenantId());
        } catch (TeamQuotaService.TeamQuotaExceededException e) {
            throw new ToolException(e.getMessage(), e);
        }
        TeamDocument saved;
        try {
            saved = teamService.create(ctx.tenantId(), name, AdminToolSupport.str(params, "title"), members);
        } catch (TeamService.TeamAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }
        admin.record(
                ctx, EddieActivityKind.TEAM_CREATED, "Team `" + name + "` angelegt", List.of(EntityRef.team(name)));
        return TeamCreateTool.row(saved);
    }

    static Map<String, Object> row(TeamDocument team) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", team.getName());
        if (team.getTitle() != null) {
            out.put("title", team.getTitle());
        }
        out.put("members", team.getMembers() == null ? List.of() : team.getMembers());
        out.put("enabled", team.isEnabled());
        return out;
    }
}
