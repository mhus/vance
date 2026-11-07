package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool: replace the states a debug run halts before — the whole set, as the run screen
 * sends it; an empty list clears. Applies to the states the run enters from now on; a run that
 * already stands before a state stays there.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowBreakpointsTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "The workflowRunId of a debug run."));
        props.put(
                "states",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "The complete set of states to halt before — it replaces the current set. "
                                + "Names are states of the run's workflow (workflow_get lists them). "
                                + "An empty list clears all breakpoints."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of("runId", "states"));
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_breakpoints";
    }

    @Override
    public String description() {
        return "Set the breakpoints of a debug run: the complete list of states it halts before "
                + "(replaces the old set; empty clears). Only a run started or restarted with "
                + "debug or breakpoints has any. See manual_read('workflow-debug').";
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
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "workflow");
    }

    @Override
    public String searchHint() {
        return "Set breakpoints on a workflow debug run";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.WRITE);
        String runId = WorkflowToolSupport.nativeRunId(params);
        List<String> states = WorkflowToolSupport.textList(params, "states");
        if (states == null) {
            throw new ToolException("'states' is required — the complete list; an empty list clears.");
        }
        if (!support.detail(scope, runId).isDebug()) {
            throw new ToolException("workflow run '" + runId + "' was not started for debugging, so it has no "
                    + "breakpoints. Restart it with workflow_restart (debug or breakpoints).");
        }
        try {
            support.registry()
                    .setBreakpoints(
                            scope.subject(),
                            scope.tenantId(),
                            scope.projectId(),
                            WorkflowToolSupport.composite(runId),
                            states);
        } catch (IllegalArgumentException ex) {
            throw new ToolException(ex.getMessage(), ex);
        }
        return WorkflowToolSupport.position(support.detail(scope, runId));
    }
}
