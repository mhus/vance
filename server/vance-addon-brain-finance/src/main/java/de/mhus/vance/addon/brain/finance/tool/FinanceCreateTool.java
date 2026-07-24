package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Create a new empty {@code kind: finance-tree} document. */
@Component
@Slf4j
public class FinanceCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Target path (auto-suffixed `.finance-tree.yaml`)."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final FinanceService financeService;

    public FinanceCreateTool(EddieContext eddieContext, FinanceService financeService) {
        this.eddieContext = eddieContext;
        this.financeService = financeService;
    }

    @Override public String name() { return "finance_tree_create"; }

    @Override
    public String description() {
        return "Create a new finance-tree (kind: finance-tree) — a hierarchical "
                + "income/expense model computed bottom-up. Starts empty; add the root "
                + "node with `finance_node_add` (no parentName), then children under it. "
                + "Run manual_read('finance-tree') for the data model.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "finance"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = FinanceToolSupport.paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String title = FinanceToolSupport.paramString(params, "title");
        String description = FinanceToolSupport.paramString(params, "description");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = financeService.create(
                ctx.tenantId(), project.getName(), path, title, description, ctx.userId());
        log.info("FinanceCreateTool path='{}'", stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        result.put("nextStep", "Add the root node via `finance_node_add` (no parentName).");
        return result;
    }
}
