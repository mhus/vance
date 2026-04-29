package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        TeamDocument team = teamService.findByTenantAndName(ctx.tenantId(), name)
                .orElseThrow(() -> new ToolException(
                        "Team '" + name + "' not found in tenant '"
                                + ctx.tenantId() + "'"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", team.getName());
        if (team.getTitle() != null) out.put("title", team.getTitle());
        out.put("members", team.getMembers() == null ? List.of() : team.getMembers());
        out.put("enabled", team.isEnabled());
        return out;
    }
}
