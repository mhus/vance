package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists teams. Two modes:
 * <ul>
 *   <li>No params: every team in the tenant.</li>
 *   <li>{@code projectId} (or active project): only teams that have
 *       access to that project (i.e. listed in
 *       {@link ProjectDocument#getTeamIds()}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TeamListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional. If given (or an active "
                                    + "project is set), filter to teams with "
                                    + "access to that project. Pass 'all' to "
                                    + "force the unfiltered tenant-wide list.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final TeamService teamService;

    @Override
    public String name() {
        return "team_list";
    }

    @Override
    public String description() {
        return "List teams. Default scope: teams with access to the "
                + "active project. Pass projectId='all' for the "
                + "tenant-wide team list.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectIdParam = paramString(params, "projectId");
        boolean wantsAll = "all".equalsIgnoreCase(projectIdParam);

        List<TeamDocument> teams = teamService.all(ctx.tenantId());
        List<TeamDocument> filtered;
        String scope;
        if (wantsAll) {
            filtered = teams;
            scope = "tenant";
        } else {
            ProjectDocument project;
            try {
                project = eddieContext.resolveProject(params, ctx, true);
            } catch (ToolException notSet) {
                // No active project and no explicit one passed → fall
                // back to tenant-wide; keep the result useful instead
                // of failing on first call.
                filtered = teams;
                scope = "tenant";
                return buildOut(filtered, scope, null);
            }
            List<String> projectTeamIds = project.getTeamIds() == null
                    ? List.of() : project.getTeamIds();
            filtered = new ArrayList<>();
            for (TeamDocument t : teams) {
                if (projectTeamIds.contains(t.getName())) {
                    filtered.add(t);
                }
            }
            scope = "project:" + project.getName();
        }

        return buildOut(filtered, scope, null);
    }

    private static Map<String, Object> buildOut(List<TeamDocument> teams, String scope,
            @org.jspecify.annotations.Nullable String projectName) {
        List<Map<String, Object>> rows = new ArrayList<>(teams.size());
        for (TeamDocument t : teams) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.getName());
            if (t.getTitle() != null) row.put("title", t.getTitle());
            row.put("memberCount", t.getMembers() == null ? 0 : t.getMembers().size());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("teams", rows);
        out.put("count", rows.size());
        return out;
    }

    private static @org.jspecify.annotations.Nullable String paramString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
