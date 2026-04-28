package de.mhus.vance.brain.tools.project;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists the projects in the current tenant. Default filter hides
 * {@link ProjectKind#SYSTEM} projects (the per-user Hub) — they are
 * infrastructure, not work projects. Pass {@code includeSystem=true}
 * to see them anyway.
 *
 * <p>Used primarily by the Vance hub engine so the user can ask
 * „welche Projekte gibt es?" and get a real answer instead of an
 * intent-without-action turn.
 */
@Component
@RequiredArgsConstructor
public class ProjectListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "includeSystem", Map.of(
                            "type", "boolean",
                            "description", "Include SYSTEM-kind projects "
                                    + "(e.g. the per-user Vance hub). "
                                    + "Defaults to false."),
                    "includeArchived", Map.of(
                            "type", "boolean",
                            "description", "Include ARCHIVED projects. "
                                    + "Defaults to false.")),
            "required", List.of());

    private final ProjectService projectService;

    @Override
    public String name() {
        return "project_list";
    }

    @Override
    public String description() {
        return "List projects in the current tenant. Returns name, "
                + "title, kind, status, projectGroupId. SYSTEM and "
                + "ARCHIVED are hidden by default.";
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
        if (ctx.tenantId() == null) {
            throw new ToolException("project_list requires a tenant scope");
        }
        boolean includeSystem = boolParam(params, "includeSystem", false);
        boolean includeArchived = boolParam(params, "includeArchived", false);

        List<ProjectDocument> all = projectService.all(ctx.tenantId());
        List<Map<String, Object>> rows = new ArrayList<>(all.size());
        for (ProjectDocument p : all) {
            if (!includeSystem && p.getKind() == ProjectKind.SYSTEM) {
                continue;
            }
            if (!includeArchived && p.getStatus() != null
                    && p.getStatus().name().equals("ARCHIVED")) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getName());
            if (p.getTitle() != null) {
                row.put("title", p.getTitle());
            }
            row.put("kind", p.getKind() == null
                    ? ProjectKind.NORMAL.name() : p.getKind().name());
            if (p.getStatus() != null) {
                row.put("status", p.getStatus().name());
            }
            if (p.getProjectGroupId() != null) {
                row.put("projectGroupId", p.getProjectGroupId());
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", rows);
        out.put("count", rows.size());
        return out;
    }

    private static boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
