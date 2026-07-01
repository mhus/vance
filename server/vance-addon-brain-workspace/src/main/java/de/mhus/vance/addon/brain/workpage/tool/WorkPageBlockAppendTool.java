package de.mhus.vance.addon.brain.workpage.tool;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
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
                put("blocks", Map.of("type", "array",
                        "description", "Multiple block specs to append in order — "
                                + "alternative to `block` (same grammar). Use either "
                                + "`block` (one) or `blocks` (many).",
                        "items", Map.of("type", "object")));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

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
        return "Append block(s) to the end of a workpage. Pass one block via "
                + "`block` or several via `blocks` (array) — block spec is "
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
        // Accept either `block` (one) or `blocks` (an array) — the LLM often
        // reaches for `blocks=[…]` by analogy with workpage_create; honouring
        // both avoids a confusing "block is required" on that natural shape.
        List<Map<String, Object>> blockRaws = new ArrayList<>();
        Map<String, Object> single = WorkPageToolSupport.paramMap(params, "block");
        if (single != null) blockRaws.add(single);
        blockRaws.addAll(WorkPageToolSupport.paramMapList(params, "blocks"));
        if (blockRaws.isEmpty()) throw new ToolException("block or blocks is required");

        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);

        DocumentDocument updated = r.doc();
        List<String> appended = new ArrayList<>();
        for (Map<String, Object> blockRaw : blockRaws) {
            Block block = WorkPageService.buildBlock(blockRaw);
            updated = workPageService.appendBlock(updated, block);
            appended.add(block.getClass().getSimpleName());
        }

        log.info("WorkPageBlockAppendTool path='{}' appended={}",
                updated.getPath(), appended);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("appendedTypes", appended);
        return result;
    }
}
