package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasService;
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
import org.springframework.stereotype.Component;

/** Create a new empty {@code kind: canvas} document (a spatial node/edge graph). */
@Component
@Slf4j
public class CanvasCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Target document path (without leading slash). "
                                + "Auto-suffixed with `.canvas.yaml` if no extension is given."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
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
        return "Create a new spatial canvas (kind: canvas) — a 2D node/edge graph. "
                + "Stored as YAML with a `$meta.kind: canvas` header. Path is "
                + "auto-suffixed with `.canvas.yaml` if no extension is given. Add "
                + "nodes with `canvas_node_add` and connect them with `canvas_edge_add`.";
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

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = canvasService.create(
                ctx.tenantId(), project.getName(), path, title, description, ctx.userId());

        log.info("CanvasCreateTool path='{}' title='{}'", stored.getPath(), title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        if (title != null) result.put("title", title);
        result.put("nextStep", "Add nodes via `canvas_node_add`, connect them via "
                + "`canvas_edge_add`.");
        return result;
    }
}
