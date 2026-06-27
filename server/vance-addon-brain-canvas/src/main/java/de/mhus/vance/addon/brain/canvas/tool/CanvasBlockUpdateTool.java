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

/** Replace the block at an anchor with a new block. */
@Component
@Slf4j
public class CanvasBlockUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("anchor", Map.of("type", "object"));
                put("block", Map.of("type", "object"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "anchor", "block"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasBlockUpdateTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_block_update"; }

    @Override
    public String description() {
        return "Replace a block at a specific anchor. Use { index: N } "
                + "or { heading: \"text\" } as the anchor.";
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
        DocumentDocument updated = canvasService.updateBlock(r.doc(), anchor, block);

        log.info("CanvasBlockUpdateTool path='{}' anchor='{}'",
                updated.getPath(), anchor);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("newType", block.getClass().getSimpleName());
        return result;
    }
}
