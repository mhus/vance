package de.mhus.vance.addon.brain.workpage.tool;

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

/** Move an existing block to a new position (zero-based index). */
@Component
@Slf4j
public class WorkPageBlockMoveTool implements Tool {

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
    private final WorkPageService workPageService;

    public WorkPageBlockMoveTool(EddieContext eddieContext,
                               DocumentService documentService,
                               WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_block_move"; }

    @Override
    public String description() {
        return "Move a block to a new index. Use { index } or { heading } "
                + "for the source, integer index for the destination.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> fromRaw = WorkPageToolSupport.paramMap(params, "from");
        if (fromRaw == null) throw new ToolException("from is required");
        WorkPageService.BlockAnchor from = WorkPageService.BlockAnchor.fromMap(fromRaw);
        int toIndex = WorkPageToolSupport.paramInt(params, "toIndex", -1);
        if (toIndex < 0) throw new ToolException("toIndex must be >= 0");

        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = workPageService.moveBlock(r.doc(), from, toIndex);

        log.info("WorkPageBlockMoveTool path='{}' from='{}' toIndex={}",
                updated.getPath(), from, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("toIndex", toIndex);
        return result;
    }
}
