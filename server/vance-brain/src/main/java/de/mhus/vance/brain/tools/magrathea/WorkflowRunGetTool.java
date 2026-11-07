package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
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
 * Agent tool: one workflow run as the run screen shows it — status and state, the steps with
 * their outcomes and outputs, the variables, sub-runs, cost, and above all what the run can be
 * told to do next ({@code allowedActions}, {@code simulation}, {@code restartableStates}).
 *
 * <p>Step outputs are cut to a short length so a long run stays readable; {@code step} gives one
 * state's output whole. {@code waitSeconds} is the one allowed wait (see
 * {@link WorkflowToolSupport}): for a run that was just started or released and is expected to
 * halt again.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowRunGetTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "runId",
                Map.of("type", "string", "description", "The workflowRunId from workflow_start or workflow_runs."));
        props.put(
                "step",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "A state name: its step output is given in full instead of cut. "
                                + "Use it when an output ends with 'outputCut'."));
        props.put(
                "waitSeconds",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Wait up to this many seconds (max " + WorkflowToolSupport.MAX_WAIT_SECONDS
                                + ") for a running run to halt, wait or end. Only for a run you have "
                                + "just started or released; never loop on this."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of("runId"));
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_run_get";
    }

    @Override
    public String description() {
        return "Inspect one workflow run: status, current state, steps with outcome and output, "
                + "variables, params, result, error, sub-runs, cost — and what it can do next: "
                + "allowedActions (pause/resume/stop/step/abort), simulation (the outcomes you may "
                + "feed a halted debug run), restartableStates. Snapshot, not a wait: optional "
                + "waitSeconds (max 20) only for a run you just released. See manual_read('workflow-debug').";
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
        return "Inspect a workflow run: steps, variables, state, what it can do next";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.READ);
        String runId = WorkflowToolSupport.nativeRunId(params);
        RunDetailDto detail = support.awaitSettled(scope, runId, null, WorkflowToolSupport.waitSeconds(params));
        return WorkflowToolSupport.view(detail, WorkflowToolSupport.text(params, "step"));
    }
}
