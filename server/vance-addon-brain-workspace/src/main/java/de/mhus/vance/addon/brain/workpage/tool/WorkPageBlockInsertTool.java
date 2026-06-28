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

/** Insert a block at an explicit anchor (index or heading-text). */
@Component
@Slf4j
public class WorkPageBlockInsertTool implements Tool {

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
    private final WorkPageService workPageService;

    public WorkPageBlockInsertTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_block_insert"; }

    @Override
    public String description() {
        return "Insert a block at a specific position. anchor is "
                + "{ index: N } or { heading: \"text\" }.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> anchorRaw = WorkPageToolSupport.paramMap(params, "anchor");
        if (anchorRaw == null) throw new ToolException("anchor is required");
        WorkPageService.BlockAnchor anchor = WorkPageService.BlockAnchor.fromMap(anchorRaw);

        Map<String, Object> blockRaw = WorkPageToolSupport.paramMap(params, "block");
        if (blockRaw == null) throw new ToolException("block is required");
        Block block = WorkPageService.buildBlock(blockRaw);

        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = workPageService.insertBlock(r.doc(), anchor, block);

        log.info("WorkPageBlockInsertTool path='{}' anchor='{}' type='{}'",
                updated.getPath(), anchor, block.getClass().getSimpleName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("insertedType", block.getClass().getSimpleName());
        return result;
    }
}
