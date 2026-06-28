package de.mhus.vance.addon.brain.workpage.tool;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
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

/** Read-only — list blocks in a workpage, optionally filtered. */
@Component
public class WorkPageQueryTool implements Tool {

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
    private final WorkPageService workPageService;

    public WorkPageQueryTool(EddieContext eddieContext,
                           DocumentService documentService,
                           WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_query"; }

    @Override
    public String description() {
        return "List blocks in a workpage. Filters: `type` (block-type "
                + "name), `contains` (substring of textual content). "
                + "Returns blocks with their resolved index — useful "
                + "as input to other workpage_* tools.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read", "document", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkPageToolSupport.Resolved r = WorkPageToolSupport.resolveByPath(
                eddieContext, documentService, params, ctx);
        String typeFilter = WorkPageToolSupport.paramString(params, "type");
        String contains = WorkPageToolSupport.paramString(params, "contains");

        // Use full list to expose absolute indices; then filter in-place.
        List<Block> all = workPageService.readDocument(r.doc()).blocks();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Block b = all.get(i);
            String simpleName = b.getClass().getSimpleName();
            if (typeFilter != null && !simpleName.equalsIgnoreCase(typeFilter)
                    && !simpleName.equalsIgnoreCase(typeFilter.replace("-", ""))) {
                continue;
            }
            if (contains != null && !WorkPageService.blockText(b)
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(contains.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            Map<String, Object> m = WorkPageService.blockToMap(b);
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
