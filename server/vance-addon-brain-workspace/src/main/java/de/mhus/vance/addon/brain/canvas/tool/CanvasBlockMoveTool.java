package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
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

/** Move an existing block to a new position (zero-based index). */
@Component
@Slf4j
public class CanvasBlockMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("from", Map.of("type", "object",
                        "description", "Source anchor — { index: N } or { heading: \"text\" }."));
                put("toIndex", Map.of("type", "integer",
                        "description", "Destination index, zero-based. Clamped to "
                                + "[0, blockCount]."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "from", "toIndex"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasBlockMoveTool(EddieContext eddieContext,
                               DocumentService documentService,
                               CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_block_move"; }

    @Override
    public String description() {
        return "Move a block to a new index. Use { index } or { heading } "
                + "for the source, integer index for the destination.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> fromRaw = CanvasToolSupport.paramMap(params, "from");
        if (fromRaw == null) throw new ToolException("from is required");
        CanvasService.BlockAnchor from = CanvasService.BlockAnchor.fromMap(fromRaw);
        int toIndex = CanvasToolSupport.paramInt(params, "toIndex", -1);
        if (toIndex < 0) throw new ToolException("toIndex must be >= 0");

        CanvasToolSupport.Resolved r = CanvasToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = canvasService.moveBlock(r.doc(), from, toIndex);

        log.info("CanvasBlockMoveTool path='{}' from='{}' toIndex={}",
                updated.getPath(), from, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("toIndex", toIndex);
        return result;
    }
}
