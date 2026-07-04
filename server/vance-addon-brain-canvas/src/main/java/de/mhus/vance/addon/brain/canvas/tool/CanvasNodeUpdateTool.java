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

/** Update a node's attributes / text / position (also covers move). */
@Component
@Slf4j
public class CanvasNodeUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Canvas document path."));
                put("id", Map.of("type", "string", "description", "Node id to update."));
                put("patch", Map.of("type", "object",
                        "description", "Fields to overwrite on the node (x, y, w, h, color, "
                                + "text, ref, href, title, label, …). Merged over the existing "
                                + "node; `id` cannot be changed. To move a node, patch `x`/`y`."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "id", "patch"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasNodeUpdateTool(EddieContext eddieContext,
                                DocumentService documentService,
                                CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_node_update"; }

    @Override
    public String description() {
        return "Update a canvas node by id — patch position, size, color or "
                + "type-specific fields. Patching `x`/`y` moves the node.";
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
        Map<String, Object> patch = CanvasToolSupport.paramMap(params, "patch");
        if (patch.isEmpty()) throw new ToolException("patch is required");

        CanvasService.MutationResult res = canvasService.updateNode(r.doc(), id, patch);
        log.info("CanvasNodeUpdateTool path='{}' nodeId='{}'", r.doc().getPath(), id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", res.doc().getPath());
        result.put("nodeId", id);
        return result;
    }
}
