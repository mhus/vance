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

/** Add a node to a canvas. */
@Component
@Slf4j
public class CanvasNodeAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Canvas document path."));
                put("node", Map.of("type", "object",
                        "description", "Node spec: { type: text|doc|link|group, x, y, w, h, "
                                + "color?, z?, … }. text→`text`, doc→`ref` (vance:-URI), "
                                + "link→`href`+`title?`, group→`label?`. `id` is minted "
                                + "server-side if omitted."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "node"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasNodeAddTool(EddieContext eddieContext,
                             DocumentService documentService,
                             CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_node_add"; }

    @Override
    public String description() {
        return "Add a node (text | doc | link | group) to a canvas at a given "
                + "x/y position. Returns the minted node id.";
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
        Map<String, Object> node = CanvasToolSupport.paramMap(params, "node");
        if (node.isEmpty()) throw new ToolException("node is required");

        CanvasService.MutationResult res = canvasService.addNode(r.doc(), node);
        log.info("CanvasNodeAddTool path='{}' nodeId='{}'", r.doc().getPath(), res.id());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", res.doc().getPath());
        result.put("nodeId", res.id());
        return result;
    }
}
