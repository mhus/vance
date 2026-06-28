package de.mhus.vance.addon.brain.workpage.tool;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
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

/** Append one block to the end of a workpage document. */
@Component
@Slf4j
public class WorkPageBlockAppendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Path of the existing workpage document."));
                put("block", Map.of("type", "object",
                        "description", "Block spec — `{ type, …fields }`. "
                                + "See workpage-blocks manual for the full grammar."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "block"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final WorkPageService workPageService;

    public WorkPageBlockAppendTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_block_append"; }

    @Override
    public String description() {
        return "Append one block to the end of a workpage. Block spec is "
                + "`{ type: 'paragraph' | 'heading' | 'todo' | 'callout' | …, "
                + "…type-specific-fields }`.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> blockRaw = WorkPageToolSupport.paramMap(params, "block");
        if (blockRaw == null) throw new ToolException("block is required");
        Block block = WorkPageService.buildBlock(blockRaw);

        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = workPageService.appendBlock(r.doc(), block);

        log.info("WorkPageBlockAppendTool path='{}' type='{}'",
                updated.getPath(), block.getClass().getSimpleName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("appendedType", block.getClass().getSimpleName());
        return result;
    }
}
