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

/** Append one block to the end of a canvas document. */
@Component
@Slf4j
public class CanvasBlockAppendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Path of the existing canvas document."));
                put("block", Map.of("type", "object",
                        "description", "Block spec — `{ type, …fields }`. "
                                + "See canvas-tools manual for the full grammar."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "block"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final CanvasService canvasService;

    public CanvasBlockAppendTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 CanvasService canvasService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.canvasService = canvasService;
    }

    @Override public String name() { return "canvas_block_append"; }

    @Override
    public String description() {
        return "Append one block to the end of a canvas. Block spec is "
                + "`{ type: 'paragraph' | 'heading' | 'todo' | 'callout' | …, "
                + "…type-specific-fields }`.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "canvas");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> blockRaw = CanvasToolSupport.paramMap(params, "block");
        if (blockRaw == null) throw new ToolException("block is required");
        Block block = CanvasService.buildBlock(blockRaw);

        CanvasToolSupport.Resolved r = CanvasToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = canvasService.appendBlock(r.doc(), block);

        log.info("CanvasBlockAppendTool path='{}' type='{}'",
                updated.getPath(), block.getClass().getSimpleName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("appendedType", block.getClass().getSimpleName());
        return result;
    }
}
