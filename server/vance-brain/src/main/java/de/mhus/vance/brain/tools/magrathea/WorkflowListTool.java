package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool: the workflow definitions this project can start by name — the cascade the REST
 * list shows. The first step of debugging a workflow is knowing which ones exist and where each
 * comes from.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowListTool implements Tool {

    private static final Map<String, Object> SCHEMA =
            Map.of("type", "object", "properties", Map.of(), "required", List.of());

    private final MagratheaWorkflowLoader loader;
    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_list";
    }

    @Override
    public String description() {
        return "List the workflow definitions startable by name in this project (project before "
                + "tenant before bundled): name, description, version, where it comes from, "
                + "and its start state. Read one with workflow_get. See manual_read('workflow-debug').";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.READ_ONLY, "workflow");
    }

    @Override
    public String searchHint() {
        return "List workflow definitions";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.READ);
        List<Map<String, Object>> rows = new ArrayList<>();
        loader.listAll(scope.tenantId(), scope.projectId()).stream()
                .sorted(Comparator.comparing(ResolvedMagratheaWorkflow::name))
                .forEach(w -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", w.name());
                    if (w.description() != null) row.put("description", w.description());
                    if (w.version() != null) row.put("version", w.version());
                    row.put("source", w.source().name());
                    row.put("start", w.startState());
                    rows.add(row);
                });
        return Map.of("workflows", rows, "count", rows.size());
    }
}
