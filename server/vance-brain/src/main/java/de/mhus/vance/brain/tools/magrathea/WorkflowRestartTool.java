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
 * Agent tool: start a <em>new</em> run from an ended one — a rerun from the start, or a retry from
 * a state with the variables the old run had on entering it. The old run's journal is untouched;
 * the new run points back at it. Always headless and owned by the caller, like
 * {@code workflow_start}, and a spawn tool for the same reason: it starts execution, so it is
 * refused in trigger-scoped runs.
 *
 * <p><b>The steps run for real again</b> from the chosen state on — a tool that writes or sends
 * does so a second time. Restarting in the debugger ({@code debug}/{@code breakpoints}) is the
 * way to get there and stop before the dangerous step.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@de.mhus.vance.toolpack.SpawnTool
public class WorkflowRestartTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "runId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "The workflowRunId of an ENDED run (done, failed or stopped)."));
        props.put(
                "fromState",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Begin in this state with the variables the old run had when it last entered it "
                                + "(retry from). One of restartableStates in workflow_run_get. Omit for a "
                                + "rerun from the start with the same parameters."));
        props.put(
                "debug", Map.of("type", "boolean", "description", "Halt before every state (step mode). Recommended."));
        props.put(
                "breakpoints",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Halt before these states only; the run goes freely in between."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of("runId"));
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_restart";
    }

    @Override
    public String description() {
        return "Start a NEW run from an ended workflow run: a rerun with the same parameters, or "
                + "fromState to retry from a state with the variables it had then (the frozen "
                + "definition of the old run is used). The steps execute for real again, side "
                + "effects included — pass debug or breakpoints to halt before the risky one, then "
                + "use workflow_simulate to skip it. Returns the new runId. "
                + "See manual_read('workflow-debug').";
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
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "workflow", ToolLabels.SIDE_EFFECT);
    }

    @Override
    public String searchHint() {
        return "Rerun a finished workflow, optionally from a state, in the debugger";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.WRITE);
        String runId = WorkflowToolSupport.nativeRunId(params);
        String fromState = WorkflowToolSupport.text(params, "fromState");
        boolean debug = Boolean.TRUE.equals(params.get("debug"));
        List<String> breakpoints = WorkflowToolSupport.textList(params, "breakpoints");

        String created;
        try {
            created = support.registry()
                    .restart(
                            scope.subject(),
                            scope.tenantId(),
                            scope.projectId(),
                            WorkflowToolSupport.composite(runId),
                            fromState,
                            ctx.userId(),
                            debug,
                            breakpoints);
        } catch (IllegalStateException ex) {
            throw new ToolException(ex.getMessage() + " — stop it first with workflow_control(action=stop).", ex);
        } catch (IllegalArgumentException ex) {
            throw new ToolException(ex.getMessage(), ex);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowRunId", WorkflowToolSupport.nativeOf(created));
        out.put("restartOf", runId);
        if (fromState != null) out.put("fromState", fromState);
        out.put("debug", debug || (breakpoints != null && !breakpoints.isEmpty()));
        return out;
    }
}
