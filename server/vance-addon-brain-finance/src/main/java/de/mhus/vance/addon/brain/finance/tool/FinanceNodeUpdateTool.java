package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Update a node's display fields (title/icon/color/sign/description/notesRef). */
@Component
public class FinanceNodeUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("name", Map.of("type", "string", "description", "Node to update."));
                put("patch", Map.of("type", "object",
                        "description", "Fields to change: title, icon, color, sign (+1/-1), "
                                + "description, notesRef. Values/children are untouched."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "name", "patch"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;

    public FinanceNodeUpdateTool(EddieContext eddieContext, DocumentService documentService,
                                 FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_node_update"; }

    @Override
    public String description() {
        return "Update a finance-tree node's display fields (title, icon, color, sign, "
                + "description, notesRef). The node name and its values/children are not "
                + "changed. Set `sign` to -1 to make the node's subtree subtract.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        FinanceToolSupport.Resolved r =
                FinanceToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        String nodeName = FinanceToolSupport.paramString(params, "name");
        if (nodeName == null) throw new ToolException("`name` is required");
        Map<String, Object> patch = FinanceToolSupport.paramMap(params, "patch");

        financeService.updateNode(r.doc(), nodeName, patch, ctx.userId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("name", nodeName);
        return result;
    }
}
