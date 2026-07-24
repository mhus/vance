package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.addon.brain.finance.FinanceTreeCodec;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
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

/** Add a node to a finance-tree (root when no parentName, else under the named parent). */
@Component
public class FinanceNodeAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("parentName", Map.of("type", "string",
                        "description", "Name of the parent node; omit to set the root."));
                put("node", Map.of("type", "object",
                        "description", "Node: {name (required), title, icon, color, "
                                + "sign (+1/-1), description, notesRef}. Add values later "
                                + "with finance_node_value_set."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "node"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;

    public FinanceNodeAddTool(EddieContext eddieContext, DocumentService documentService,
                              FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_node_add"; }

    @Override
    public String description() {
        return "Add a node to a finance-tree. Omit `parentName` to create the root; "
                + "otherwise the node is appended under the named parent. A node's `sign` "
                + "(-1) flips its whole subtree (use it for expense branches). Names must "
                + "be unique in the tree.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        FinanceToolSupport.Resolved r =
                FinanceToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        Map<String, Object> nodeMap = FinanceToolSupport.paramMap(params, "node");
        if (nodeMap.isEmpty()) throw new ToolException("`node` is required");
        FinanceNode node = FinanceTreeCodec.nodeFromMap(nodeMap);
        String parentName = FinanceToolSupport.paramString(params, "parentName");

        financeService.addNode(r.doc(), parentName, node, ctx.userId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("name", node.name());
        result.put("parentName", parentName);
        return result;
    }
}
