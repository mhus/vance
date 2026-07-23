package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdConfig;
import de.mhus.vance.addon.brain.gtd.GtdFolderReader;
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

/** Create a processed GTD action (under actions/ or projects/&lt;project&gt;/). */
@Component
@Slf4j
public class GtdActionCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("title", Map.of("type", "string"));
                put("when", Map.of("type", "string",
                        "description", "'' (Anytime) | today | someday | ISO date (Upcoming/Today)."));
                put("deadline", Map.of("type", "string", "description", "Optional hard due date (ISO)."));
                put("contexts", Map.of("type", "array", "items", Map.of("type", "string")));
                put("project", Map.of("type", "string", "description", "Optional project (folder under projects/)."));
                put("body", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "title"));

    private final EddieContext eddieContext;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;

    public GtdActionCreateTool(EddieContext eddieContext, GtdFolderReader folderReader, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_action_create"; }

    @Override
    public String description() {
        return "Create a processed GTD action. Set `when` to bucket it: '' = Anytime, "
                + "today, someday, or an ISO date (future = Upcoming, slides into Today on the "
                + "day). Optional deadline, contexts (@calls/@home/…) and project. Do NOT try to "
                + "put it into a bucket folder — buckets are derived from `when`.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "gtd"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String title = paramString(params, "title");
        if (title == null) throw new ToolException("title is required");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        GtdConfig config = folderReader.scan(ctx.tenantId(), project.getName(), folder).config();
        DocumentDocument doc = gtdService.createAction(ctx.tenantId(), project.getName(), folder,
                config, title, paramString(params, "when"), paramString(params, "deadline"),
                paramStringList(params, "contexts"), paramString(params, "project"),
                paramString(params, "body"), ctx.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", doc.getPath());
        result.put("id", doc.getId());
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
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
