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

/** Delete the block at an anchor. */
@Component
@Slf4j
public class WorkPageBlockDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("anchor", Map.of("type", "object"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "anchor"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final WorkPageService workPageService;

    public WorkPageBlockDeleteTool(EddieContext eddieContext,
                                 DocumentService documentService,
                                 WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_block_delete"; }

    @Override
    public String description() {
        return "Delete a block at a specific anchor. Use { index: N } or "
                + "{ heading: \"text\" }.";
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

        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        DocumentDocument updated = workPageService.deleteBlock(r.doc(), anchor);

        log.info("WorkPageBlockDeleteTool path='{}' anchor='{}'",
                updated.getPath(), anchor);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", updated.getPath());
        result.put("deletedAnchor", anchor.toString());
        return result;
    }
}
