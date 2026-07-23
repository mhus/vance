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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Quick GTD capture into the Inbox — the fast, unprocessed path. */
@Component
@Slf4j
public class GtdCaptureTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("title", Map.of("type", "string"));
                put("note", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "title"));

    private final EddieContext eddieContext;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;

    public GtdCaptureTool(EddieContext eddieContext, GtdFolderReader folderReader, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_capture"; }

    @Override
    public String description() {
        return "Capture a thought into the GTD Inbox (unprocessed). The fast path — "
                + "just a title (+ optional note). Process it later by setting `when` via "
                + "gtd_action_update. Run app_rebuild afterwards to refresh the views.";
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
        DocumentDocument doc = gtdService.capture(ctx.tenantId(), project.getName(), folder,
                config, title, paramString(params, "note"), ctx.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", doc.getPath());
        result.put("id", doc.getId());
        result.put("bucket", "inbox");
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
