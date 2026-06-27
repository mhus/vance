package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.Block;
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

/** Insert a block at an explicit anchor (index or heading-text). */
@Component
@Slf4j
public class CanvasBlockInsertTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("anchor", Map.of("type", "object",
                        "description", "Either `{ index: N }` (zero-based, N may "
                                + "equal block-count for insert-at-end) or "
                                + "`{ heading: \"text\" }` (insert AT that heading's "
                                + "position; throws on duplicate headings)."));
                put("block", Map.of("type", "object"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "anchor", "block"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasBlockInsertTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_block_insert"; }

    @Override
    public String description() {
        return "Insert a block at a specific position. anchor is "
                + "{ index: N } or { heading: \"text\" }.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> anchorRaw = CanvasToolSupport.paramMap(params, "anchor");
        if (anchorRaw == null) throw new ToolException("anchor is required");
        CanvasService.BlockAnchor anchor = CanvasService.BlockAnchor.fromMap(anchorRaw);

        Map<String, Object> blockRaw = CanvasToolSupport.paramMap(params, "block");
        if (blockRaw == null) throw new ToolException("block is required");
        Block block = CanvasService.buildBlock(blockRaw);

        CanvasToolSupport.Resolved r = CanvasToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = canvasService.insertBlock(r.doc(), anchor, block);

        log.info("CanvasBlockInsertTool path='{}' anchor='{}' type='{}'",
                updated.getPath(), anchor, block.getClass().getSimpleName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("insertedType", block.getClass().getSimpleName());
        return result;
    }
}
