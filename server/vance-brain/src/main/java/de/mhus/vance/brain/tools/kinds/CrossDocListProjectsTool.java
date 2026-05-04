package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List all projects in the caller's tenant — useful when an LLM is
 * about to do a cross-project copy / move and needs to know what
 * targets exist. System projects (name starts with {@code _}) are
 * included by default; pass {@code includeSystem=false} to exclude.
 */
@Component
@RequiredArgsConstructor
public class CrossDocListProjectsTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "includeSystem", Map.of("type", "boolean",
                            "description", "Include system projects (name starts with `_`). Default: true.")),
            "required", List.of());

    private final ProjectService projectService;

    @Override public String name() { return "cross_doc_list_projects"; }
    @Override public String description() {
        return "List every project in the caller's tenant. Returns name + title + project group "
                + "+ kind. Useful before calling cross_doc_copy / cross_doc_move so you know which "
                + "targets exist.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("cross-project"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        boolean includeSystem = params == null
                || !(params.get("includeSystem") instanceof Boolean b)
                || b;
        List<ProjectDocument> projects = projectService.all(ctx.tenantId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectDocument p : projects) {
            if (!includeSystem && p.getName() != null
                    && p.getName().startsWith(ProjectService.SYSTEM_NAME_PREFIX)) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", p.getName());
            if (p.getTitle() != null) r.put("title", p.getTitle());
            if (p.getProjectGroupId() != null) r.put("projectGroupId", p.getProjectGroupId());
            if (p.getKind() != null) r.put("kind", p.getKind().name());
            if (p.getStatus() != null) r.put("status", p.getStatus().name());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", ctx.tenantId());
        out.put("count", rows.size());
        out.put("projects", rows);
        return out;
    }
}
