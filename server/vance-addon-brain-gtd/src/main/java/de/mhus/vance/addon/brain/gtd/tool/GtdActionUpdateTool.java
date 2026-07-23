package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Patch a GTD action in place. Set `when` to change its bucket (the core GTD
 * move — Today/Anytime/Someday/Upcoming all live in the `when` attribute),
 * toggle `done`, or edit contexts/deadline/title/body.
 */
@Component
@Slf4j
public class GtdActionUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("path", Map.of("type", "string", "description", "Full document path of the action."));
                put("when", Map.of("type", "string",
                        "description", "'' (Anytime) | today | someday | ISO date. Sets the bucket."));
                put("deadline", Map.of("type", "string"));
                put("contexts", Map.of("type", "array", "items", Map.of("type", "string")));
                put("done", Map.of("type", "boolean"));
                put("title", Map.of("type", "string"));
                put("body", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "path"));

    private final EddieContext eddieContext;
    private final GtdService gtdService;

    public GtdActionUpdateTool(EddieContext eddieContext, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_action_update"; }

    @Override
    public String description() {
        return "Update a GTD action in place. Change its bucket by setting `when` "
                + "('' = Anytime, today, someday, or an ISO date). Mark it complete with "
                + "done=true. Also edits contexts, deadline, title, body. Run app_rebuild "
                + "afterwards to refresh the views.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "gtd"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument doc = gtdService.updateAction(ctx.tenantId(), project.getName(), path,
                paramString(params, "when"), paramString(params, "deadline"),
                paramStringList(params, "contexts"), paramBoolean(params, "done"),
                paramString(params, "title"), paramString(params, "body"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", doc.getPath());
        result.put("id", doc.getId());
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static @Nullable Boolean paramBoolean(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return null;
    }
    private static @Nullable List<String> paramStringList(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) if (!part.isBlank()) out.add(part.trim());
            return out;
        }
        return null;
    }
}
