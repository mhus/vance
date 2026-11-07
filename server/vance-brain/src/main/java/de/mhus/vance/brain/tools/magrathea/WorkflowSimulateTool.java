package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSimulationDto;
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
 * Agent tool: give a halted run a result <em>instead of</em> executing the state it stands
 * before. Nothing of the task runs — no tool call, script, agent or wait — which makes this the
 * safe way past a step that would send, write or cost something while the steps after it are
 * being looked at. Also the way to take a branch on purpose ("what if this had failed").
 *
 * <p>Whether the run offers it is the run's own answer ({@code simulation} in the run view);
 * a run that offers nothing is told so rather than silently ignored.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowSimulateTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "The workflowRunId of a halted debug run."));
        props.put(
                "outcome",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "The result the state is to have: one of simulation.outcomes in workflow_run_get "
                                + "(the on: keys, then the catch: error kinds). Any other would fail the run."));
        props.put(
                "output",
                Map.of(
                        "description",
                        "What the state produced — plain text or JSON; it is what storeAs: stores and later "
                                + "states read. Optional."));
        props.put(
                "errorMessage",
                Map.of("type", "string", "description", "For a failure outcome: the error text. Optional."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of("runId", "outcome"));
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_simulate";
    }

    @Override
    public String description() {
        return "Skip the next state of a halted debug run and give it a result instead: the state is "
                + "NOT executed (no tool, script, agent, wait), the outcome and output you give are "
                + "journalled as its result and the run goes its normal way, then halts again. Use it "
                + "to get past a step with side effects or to force a branch. Valid outcomes: "
                + "simulation.outcomes in workflow_run_get. See manual_read('workflow-debug').";
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
        return "Skip a workflow state with a simulated result";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.WRITE);
        String runId = WorkflowToolSupport.nativeRunId(params);
        String outcome = WorkflowToolSupport.text(params, "outcome");
        if (outcome == null) {
            throw new ToolException("'outcome' is required — see simulation.outcomes in workflow_run_get.");
        }
        RunDetailDto before = support.detail(scope, runId);
        RunSimulationDto offered = before.getSimulation();
        if (offered == null) {
            throw new ToolException("workflow run '" + runId + "' offers no simulation now — it must be a "
                    + "debug run, halted before a state that can be simulated (not a condition or terminal). "
                    + "workflow_run_get shows 'simulation' when it does.");
        }
        try {
            support.registry()
                    .simulate(
                            scope.subject(),
                            scope.tenantId(),
                            scope.projectId(),
                            WorkflowToolSupport.composite(runId),
                            outcome,
                            params.get("output"),
                            WorkflowToolSupport.text(params, "errorMessage"));
        } catch (IllegalArgumentException ex) {
            throw new ToolException(ex.getMessage() + " — valid outcomes: " + offered.getOutcomes(), ex);
        }
        Map<String, Object> out =
                new LinkedHashMap<>(WorkflowToolSupport.position(support.awaitSettled(scope, runId, before, 2)));
        out.put("simulatedState", offered.getState());
        out.put("outcome", outcome);
        return out;
    }
}
