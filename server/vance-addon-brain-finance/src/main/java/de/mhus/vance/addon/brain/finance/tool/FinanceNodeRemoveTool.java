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

/** Remove a node (and its subtree) from a finance-tree. */
@Component
public class FinanceNodeRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("name", Map.of("type", "string", "description", "Node to remove."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "name"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;

    public FinanceNodeRemoveTool(EddieContext eddieContext, DocumentService documentService,
                                 FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_node_remove"; }

    @Override
    public String description() {
        return "Remove a node and its whole subtree from a finance-tree. Removing the "
                + "root clears the tree.";
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

        financeService.removeNode(r.doc(), nodeName, ctx.userId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("removed", nodeName);
        return result;
    }
}
