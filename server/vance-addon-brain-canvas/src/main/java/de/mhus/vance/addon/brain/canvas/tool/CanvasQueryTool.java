package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Read-only: list / filter canvas nodes. */
@Component
@Slf4j
public class CanvasQueryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Canvas document path."));
                put("type", Map.of("type", "string", "enum", List.of("text", "doc", "link", "group"),
                        "description", "Optional node-type filter."));
                put("textContains", Map.of("type", "string",
                        "description", "Optional case-insensitive substring filter over node text."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasQueryTool(EddieContext eddieContext,
                           DocumentService documentService,
                           CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_query"; }

    @Override public String description() {
        return "List canvas nodes, optionally filtered by type and/or a "
                + "text substring. Read-only.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read-only", "document", "canvas");
    }

    @Override public boolean contributesPrak() { return false; }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        CanvasToolSupport.Resolved r =
                CanvasToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        String typeFilter = CanvasToolSupport.paramString(params, "type");
        String textContains = CanvasToolSupport.paramString(params, "textContains");

        List<Map<String, Object>> nodes = canvasService.query(r.doc(), typeFilter, textContains);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("count", nodes.size());
        result.put("nodes", nodes);
        return result;
    }
}
