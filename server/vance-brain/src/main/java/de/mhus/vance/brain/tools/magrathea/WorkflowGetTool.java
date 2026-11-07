package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaParameterSpec;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool: one workflow definition — its YAML, its state names and its parameters. The state
 * names are what {@code workflow_start} and {@code workflow_breakpoints} accept as breakpoints;
 * the YAML is what a run's steps are read against.
 *
 * <p>A run keeps the <em>frozen</em> definition it started with, which may differ from the file
 * today; this tool shows the current one.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowGetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "name",
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Workflow name as workflow_list shows it (the cascade name, not a path).")),
            "required",
            List.of("name"));

    private final MagratheaWorkflowLoader loader;
    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_get";
    }

    @Override
    public String description() {
        return "Read one workflow definition by name: its YAML, state names (valid breakpoints), "
                + "start state and parameters. This is the current definition; a run keeps the "
                + "one it started with. See manual_read('workflow-debug').";
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
        return "Read a workflow definition and its states";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.READ);
        String name = WorkflowToolSupport.text(params, "name");
        if (name == null) {
            throw new ToolException("'name' is required — a workflow name from workflow_list.");
        }
        ResolvedMagratheaWorkflow w = loader.load(scope.tenantId(), scope.projectId(), name.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ToolException(
                        "no workflow '" + name + "' in this project — " + "workflow_list shows the names."));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", w.name());
        if (w.description() != null) out.put("description", w.description());
        if (w.version() != null) out.put("version", w.version());
        out.put("source", w.source().name());
        out.put("start", w.startState());
        out.put("states", new ArrayList<>(w.states().keySet()));
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Map.Entry<String, MagratheaParameterSpec> e : w.parameters().entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", e.getKey());
            p.put("type", e.getValue().type());
            p.put("required", e.getValue().required());
            if (e.getValue().defaultValue() != null)
                p.put("default", e.getValue().defaultValue());
            parameters.add(p);
        }
        out.put("parameters", parameters);
        out.put("yaml", w.yaml());
        return out;
    }
}
