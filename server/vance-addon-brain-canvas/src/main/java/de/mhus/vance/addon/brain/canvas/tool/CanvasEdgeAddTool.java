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

/** Connect two nodes with a directed edge (arrow). */
@Component
@Slf4j
public class CanvasEdgeAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Canvas document path."));
                put("from", Map.of("type", "string", "description", "Source node id."));
                put("to", Map.of("type", "string", "description", "Target node id."));
                put("label", Map.of("type", "string"));
                put("fromSide", Map.of("type", "string", "enum", List.of("top", "right", "bottom", "left")));
                put("toSide", Map.of("type", "string", "enum", List.of("top", "right", "bottom", "left")));
                put("fromEnd", Map.of("type", "string", "enum", List.of("none", "arrow"),
                        "description", "Arrow head at the source. Default: none."));
                put("toEnd", Map.of("type", "string", "enum", List.of("none", "arrow"),
                        "description", "Arrow head at the target. Default: arrow (from → to)."));
                put("color", Map.of("type", "string"));
                put("dashed", Map.of("type", "boolean", "description", "Dashed line."));
                put("width", Map.of("type", "number", "description", "Stroke width in px (e.g. 3 = bold)."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "from", "to"));

    private static final List<String> EDGE_KEYS =
            List.of("from", "to", "label", "fromSide", "toSide", "fromEnd", "toEnd",
                    "color", "dashed", "width");

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasEdgeAddTool(EddieContext eddieContext,
                             DocumentService documentService,
                             CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_edge_add"; }

    @Override
    public String description() {
        return "Connect two canvas nodes with a directed edge. Default is a "
                + "`from → to` arrow; set fromEnd/toEnd to change arrow heads. "
                + "Returns the minted edge id.";
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

        Map<String, Object> edge = new LinkedHashMap<>();
        for (String key : EDGE_KEYS) {
            Object v = params.get(key);
            if (v != null) edge.put(key, v);
        }
        if (!edge.containsKey("from") || !edge.containsKey("to")) {
            throw new ToolException("from and to are required");
        }

        CanvasService.MutationResult res = canvasService.addEdge(r.doc(), edge);
        log.info("CanvasEdgeAddTool path='{}' edgeId='{}' {} -> {}",
                r.doc().getPath(), res.id(), edge.get("from"), edge.get("to"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", res.doc().getPath());
        result.put("edgeId", res.id());
        return result;
    }
}
