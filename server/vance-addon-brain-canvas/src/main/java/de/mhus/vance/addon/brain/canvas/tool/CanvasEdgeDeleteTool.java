package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Delete an edge by id. */
@Component
@Slf4j
public class CanvasEdgeDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Canvas document path."));
                put("id", Map.of("type", "string", "description", "Edge id to delete."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "id"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasEdgeDeleteTool(EddieContext eddieContext,
                                DocumentService documentService,
                                CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_edge_delete"; }

    @Override public String description() {
        return "Delete a canvas edge by id.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        CanvasToolSupport.Resolved r =
                CanvasToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        String id = CanvasToolSupport.paramString(params, "id");
        if (id == null) throw new ToolException("id is required");

        CanvasService.MutationResult res = canvasService.deleteEdge(r.doc(), id);
        log.info("CanvasEdgeDeleteTool path='{}' edgeId='{}'", r.doc().getPath(), id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", res.doc().getPath());
        result.put("deletedEdgeId", id);
        return result;
    }
}
