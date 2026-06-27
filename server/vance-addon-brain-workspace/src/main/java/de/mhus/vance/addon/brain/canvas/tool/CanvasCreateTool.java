package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.Block;
import de.mhus.vance.addon.brain.canvas.CanvasService;
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
import org.springframework.stereotype.Component;

/** Create a new {@code kind: canvas} document with an initial block list. */
@Component
@Slf4j
public class CanvasCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Target document path (without leading slash). "
                                + "Extension is auto-appended to `.canvas.md` if missing."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("blocks", Map.of("type", "array",
                        "description", "Optional initial block list. Each entry "
                                + "is `{ type, …fields }` — see canvas-tools manual.",
                        "items", Map.of("type", "object")));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final CanvasService canvasService;

    public CanvasCreateTool(EddieContext eddieContext, CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_create"; }

    @Override
    public String description() {
        return "Create a new linear block-document (kind: canvas). Stored as "
                + "Markdown with a `$meta.kind: canvas` header. Path is "
                + "auto-suffixed with `.canvas.md` if no extension is given. "
                + "Optional `blocks` array seeds the document content.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = CanvasToolSupport.paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String title = CanvasToolSupport.paramString(params, "title");
        String description = CanvasToolSupport.paramString(params, "description");

        List<Block> initial = new ArrayList<>();
        for (Map<String, Object> raw : CanvasToolSupport.paramMapList(params, "blocks")) {
            initial.add(CanvasService.buildBlock(raw));
        }

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = canvasService.create(
                ctx.tenantId(), project.getName(), path,
                title, description, initial, ctx.userId());

        log.info("CanvasCreateTool path='{}' blocks={} title='{}'",
                stored.getPath(), initial.size(), title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        result.put("blockCount", initial.size());
        if (title != null) result.put("title", title);
        result.put("nextStep", "Add more blocks via `canvas_block_append` or "
                + "edit individual blocks via `canvas_block_update`.");
        return result;
    }
}
