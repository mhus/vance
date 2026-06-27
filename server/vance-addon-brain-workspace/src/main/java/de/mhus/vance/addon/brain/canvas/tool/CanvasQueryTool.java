package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.Block;
import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read-only — list blocks in a canvas, optionally filtered. */
@Component
public class CanvasQueryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("type", Map.of("type", "string",
                        "description", "Optional block-type filter (e.g. 'heading', "
                                + "'todo', 'callout'). Case-insensitive simple-name match."));
                put("contains", Map.of("type", "string",
                        "description", "Optional case-insensitive substring filter on the "
                                + "block's textual content."));
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

    @Override
    public String description() {
        return "List blocks in a canvas. Filters: `type` (block-type "
                + "name), `contains` (substring of textual content). "
                + "Returns blocks with their resolved index — useful "
                + "as input to other canvas_* tools.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        CanvasToolSupport.Resolved r = CanvasToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        String typeFilter = CanvasToolSupport.paramString(params, "type");
        String contains = CanvasToolSupport.paramString(params, "contains");

        // Use full list to expose absolute indices; then filter in-place.
        List<Block> all = canvasService.readDocument(r.doc()).blocks();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Block b = all.get(i);
            String simpleName = b.getClass().getSimpleName();
            if (typeFilter != null && !simpleName.equalsIgnoreCase(typeFilter)
                    && !simpleName.equalsIgnoreCase(typeFilter.replace("-", ""))) {
                continue;
            }
            if (contains != null && !CanvasService.blockText(b)
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(contains.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            Map<String, Object> m = CanvasService.blockToMap(b);
            m.put("index", i);
            matches.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("totalBlocks", all.size());
        result.put("matchCount", matches.size());
        result.put("blocks", matches);
        return result;
    }
}
