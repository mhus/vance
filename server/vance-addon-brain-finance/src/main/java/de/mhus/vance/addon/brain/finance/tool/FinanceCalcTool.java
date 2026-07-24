package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.addon.brain.finance.model.FinanceComputed;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Recompute the current-value snapshot of a finance-tree and write it back. */
@Component
public class FinanceCalcTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final FinanceService financeService;

    public FinanceCalcTool(EddieContext eddieContext, DocumentService documentService,
                           FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_tree_calc"; }

    @Override
    public String description() {
        return "Recompute the current-value snapshot (\"reload\") of a finance-tree: "
                + "per-year rate per node (sign-rolled bottom-up), plus a separate "
                + "one-time sum. Writes the result under $computed and returns it.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        FinanceToolSupport.Resolved r =
                FinanceToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        FinanceComputed computed = financeService.recalculate(r.doc(), ctx.userId());

        Map<String, Object> nodes = new LinkedHashMap<>();
        for (NodeSnapshot s : computed.nodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("perYear", s.perYear());
            n.put("perMonth", s.perMonth());
            n.put("oneTimeSum", s.oneTimeSum());
            nodes.put(s.name(), n);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", r.doc().getPath());
        result.put("computedAt", computed.computedAt());
        result.put("nodes", nodes);
        return result;
    }
}
