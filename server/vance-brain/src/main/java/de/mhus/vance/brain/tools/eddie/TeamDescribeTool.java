package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Returns a team's full record — title, members, enabled flag —
 * which {@code team_list} omits to keep its rows compact.
 */
@Component
@RequiredArgsConstructor
public class TeamDescribeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Team name (use team_list to discover).")),
            "required", List.of("name"));

    private final TeamService teamService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "team_describe";
    }

    @Override
    public String description() {
        return "Describe a team — title, member usernames, enabled "
                + "flag. Use team_list to discover team names.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("eddie", "read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        TeamDocument team = teamService.findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException(
                        "Team '" + name + "' not found in tenant '"
                                + ctx.tenantId() + "'"));
        // Visibility gate — mirror team_list: the full member roster is only
        // visible to a Tenant ADMIN or a member of the team. Otherwise any
        // tenant user could dump another team's usernames by guessing its name
        // (the leak team_list deliberately reduces to memberCount).
        boolean admin = permissionService.check(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new de.mhus.vance.shared.permission.Resource.Tenant(ctx.tenantId()),
                de.mhus.vance.shared.permission.Action.ADMIN);
        boolean member = ctx.userId() != null && team.getMembers() != null
                && team.getMembers().contains(ctx.userId());
        if (!admin && !member) {
            throw new ToolException(
                    "not authorized to view team '" + name + "' — membership or tenant-admin required");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", team.getName());
        if (team.getTitle() != null) out.put("title", team.getTitle());
        out.put("members", team.getMembers() == null ? List.of() : team.getMembers());
        out.put("enabled", team.isEnabled());
        return out;
    }
}
