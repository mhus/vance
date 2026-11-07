package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool: the run screen's control verbs — pause, resume, stop, step, abort.
 *
 * <p>An action the run does not currently offer is not performed, and the answer says so
 * ({@code applied: false}, with what <em>is</em> offered). The screen treats it as a harmless
 * no-op because its button came from a snapshot; a model has no button, so for it the silent
 * version would read as success.
 *
 * <p>{@code step} and {@code resume} release a halted run; {@code waitSeconds} then waits for the
 * run to halt again or end, which is what the next question always is.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowControlTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "The workflowRunId."));
        props.put(
                "action",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        Arrays.stream(RunAction.values())
                                .map(a -> a.name().toLowerCase(Locale.ROOT))
                                .toList(),
                        "description",
                        "step: run exactly the next state of a halted debug run, then halt again. "
                                + "resume: go on (a debug run then runs to the next breakpoint or the end). "
                                + "pause: start nothing new. stop: end the run. "
                                + "abort: end the task the run waits on as 'cancelled' — the run takes its "
                                + "catch: way and stays alive."));
        props.put("reason", Map.of("type", "string", "description", "Why — recorded with a stop or abort."));
        props.put(
                "waitSeconds",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "After step/resume: wait up to this many seconds (max "
                                + WorkflowToolSupport.MAX_WAIT_SECONDS
                                + ") for the run to halt again or end."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of("runId", "action"));
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_control";
    }

    @Override
    public String description() {
        return "Steer a workflow run: step, resume, pause, stop, abort. Only what the run offers "
                + "right now is performed — workflow_run_get shows allowedActions; otherwise "
                + "applied is false. Debug runs really execute their states (tools, shell, agents "
                + "run), step by step. Stop ends the run for good. See manual_read('workflow-debug').";
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
        return "Step, resume, pause, stop or abort a workflow run";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.WRITE);
        String runId = WorkflowToolSupport.nativeRunId(params);
        RunAction action = parseAction(WorkflowToolSupport.text(params, "action"));
        String reason = WorkflowToolSupport.text(params, "reason");

        RunDetailDto before = support.detail(scope, runId);
        boolean offered = before.getAllowedActions().contains(action);
        if (offered) {
            support.registry()
                    .perform(
                            scope.subject(),
                            scope.tenantId(),
                            scope.projectId(),
                            WorkflowToolSupport.composite(runId),
                            action,
                            reason == null ? "agent tool" : reason);
        }

        // Only a release moves the run on by itself; the others settle at once.
        boolean releases = action == RunAction.STEP || action == RunAction.RESUME;
        RunDetailDto after = offered && releases
                ? support.awaitSettled(scope, runId, before, WorkflowToolSupport.waitSeconds(params))
                : support.detail(scope, runId);

        Map<String, Object> out = new LinkedHashMap<>(WorkflowToolSupport.position(after));
        out.put("action", action.name().toLowerCase(Locale.ROOT));
        out.put("applied", offered);
        if (!offered) {
            out.put(
                    "note",
                    "'" + action.name().toLowerCase(Locale.ROOT) + "' is not offered while the run is "
                            + after.getSummary().getStatus() + " — allowedActions says what is.");
        } else if (releases && !WorkflowToolSupport.settled(after, before)) {
            out.put("note", "the run is still going; look again with workflow_run_get.");
        }
        return out;
    }

    private static RunAction parseAction(@Nullable String raw) {
        if (raw == null) {
            throw new ToolException("'action' is required: step, resume, pause, stop or abort.");
        }
        try {
            return RunAction.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolException("unknown action '" + raw + "' — step, resume, pause, stop or abort.", ex);
        }
    }
}
