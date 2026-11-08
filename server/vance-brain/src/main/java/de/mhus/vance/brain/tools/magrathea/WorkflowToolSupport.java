package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunChildDto;
import de.mhus.vance.api.runs.RunCostDto;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSimulationDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.runs.MagratheaRunSource;
import de.mhus.vance.brain.runs.RunId;
import de.mhus.vance.brain.runs.RunSourceRegistry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * What every {@code workflow_*} tool that looks at or steers a <em>run</em> needs: who is asking,
 * may they, which run is meant, and what a run looks like to a model.
 *
 * <p><b>The run view is the tools' only door.</b> Everything goes through {@link RunSourceRegistry}
 * — the same layer the run screen uses — so the debug rules (what a halted run offers, which
 * outcome a simulation accepts, what a restart may start from) live in one place and a tool cannot
 * drift from the screen. The registry speaks composite ids ({@code workflow:<id>}); the tools speak
 * the native {@code workflowRunId} that {@code workflow_start} returned, and a composite id is
 * accepted as the obvious slip.
 *
 * <p><b>Permission is the screen's:</b> {@code Project READ} to look, {@code Project WRITE} to
 * steer. A call without a bound user acts as the system subject, the same reading
 * {@code workflow_start} has.
 *
 * <p><b>Waiting is bounded.</b> The manual {@code plans} tells agents not to poll a run, and a
 * tool that returns a snapshot invites exactly that. {@code waitSeconds} is the answer for the one
 * case where waiting is the point — a debug run that has just been told to go on — and it is
 * capped, so a tool call never holds a turn for long.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
class WorkflowToolSupport {

    /** Longest wait a tool call may ask for. */
    static final int MAX_WAIT_SECONDS = 20;

    /** Step output length in a run view; one step can be read in full with {@code step}. */
    static final int STEP_OUTPUT_CHARS = 1_500;

    private static final long POLL_MILLIS = 250;

    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final RunSourceRegistry registry;

    /** Who is asking, and where: the tenant and project of the call plus the subject to check. */
    record Scope(String tenantId, String projectId, SecurityContext subject) {}

    /** The caller's scope, after the project check for {@code action}. */
    Scope scope(String toolName, ToolInvocationContext ctx, Action action) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (tenantId == null || tenantId.isBlank() || projectId == null || projectId.isBlank()) {
            throw new ToolException(toolName + " requires a project context");
        }
        SecurityContext subject = contextFactory.forToolSubject(ctx);
        permissionService.enforce(subject, new Resource.Project(tenantId, projectId), action);
        return new Scope(tenantId, projectId, subject);
    }

    /** The native run id of a tool argument: {@code workflow:<id>} is read as {@code <id>}. */
    static String nativeRunId(Map<String, Object> params) {
        Object raw = params.get("runId");
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'runId' is required — the workflowRunId that workflow_start "
                    + "returned or workflow_runs lists.");
        }
        String id = s.trim();
        RunId parsed = RunId.parse(id);
        if (parsed != null && MagratheaRunSource.SOURCE_ID.equals(parsed.source())) {
            return parsed.nativeId();
        }
        return id;
    }

    /** The run, or a refusal. Invisible and absent read the same, as they do on the screen. */
    RunDetailDto detail(Scope scope, String runId) {
        return registry.get(scope.subject(), scope.tenantId(), scope.projectId(), composite(runId))
                .orElseThrow(() -> new ToolException("no workflow run '" + runId + "' in this project."));
    }

    /** Workflow runs of the project, newest first. */
    List<RunSummaryDto> list(Scope scope, int limit) {
        return registry.list(scope.subject(), scope.tenantId(), scope.projectId(), limit, MagratheaRunSource.SOURCE_ID);
    }

    RunSourceRegistry registry() {
        return registry;
    }

    static String composite(String runId) {
        return RunId.of(MagratheaRunSource.SOURCE_ID, runId).composite();
    }

    /** Native id out of a composite one, for runs the view names (children, restarts). */
    static String nativeOf(String composite) {
        RunId id = RunId.parse(composite);
        return id == null ? composite : id.nativeId();
    }

    /**
     * Wait until the run is no longer running, at most {@code seconds}.
     *
     * <p>"No longer running" means halted, waiting, or ended. When {@code before} is given the run
     * must also have <em>moved</em> since — a different status or step — because a run that was
     * halted and has just been told to go on still reads as halted for an instant, and returning
     * that would answer a question nobody asked.
     *
     * @return the run as it stands when the wait ends, settled or not
     */
    RunDetailDto awaitSettled(Scope scope, String runId, @Nullable RunDetailDto before, int seconds) {
        RunDetailDto current = detail(scope, runId);
        if (seconds <= 0) return current;
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!settled(current, before) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ToolException("interrupted while waiting for workflow run '" + runId + "'", ex);
            }
            current = detail(scope, runId);
        }
        return current;
    }

    /** Whether {@link #awaitSettled} would stop waiting at this view. */
    static boolean settled(RunDetailDto now, @Nullable RunDetailDto before) {
        RunStatus status = now.getSummary().getStatus();
        if (status == RunStatus.RUNNING || status == RunStatus.STOPPING) return false;
        if (before == null) return true;
        return status != before.getSummary().getStatus()
                || !Objects.equals(
                        now.getSummary().getStep(), before.getSummary().getStep());
    }

    /** Wait argument in seconds, clamped to {@link #MAX_WAIT_SECONDS}; absent means no wait. */
    static int waitSeconds(Map<String, Object> params) {
        Object raw = params.get("waitSeconds");
        if (raw == null) return 0;
        if (!(raw instanceof Number n)) throw new ToolException("'waitSeconds' must be a number");
        return Math.min(Math.max(0, n.intValue()), MAX_WAIT_SECONDS);
    }

    /**
     * A run as a model reads it: where it stands, what it can be told to do, and how it got here.
     *
     * @param fullStep a state whose step output is given in full; every other step is cut to
     *        {@link #STEP_OUTPUT_CHARS}. {@code null} cuts them all.
     */
    static Map<String, Object> view(RunDetailDto d, @Nullable String fullStep) {
        RunSummaryDto s = d.getSummary();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", nativeOf(s.getRunId()));
        out.put("workflow", s.getName());
        out.put("status", s.getStatus() == null ? null : s.getStatus().name());
        out.put("state", s.getStep());
        out.put("startedAt", Objects.toString(s.getStartedAt(), null));
        out.put("updatedAt", Objects.toString(s.getUpdatedAt(), null));
        putIfPresent(out, "startedBy", s.getStartedBy());
        if (s.getParentRunId() != null) out.put("parentRunId", nativeOf(s.getParentRunId()));
        putIfPresent(out, "errorMessage", d.getErrorMessage());
        putIfPresent(out, "waitingOnInboxItemId", d.getWaitingOnInboxItemId());
        out.put("allowedActions", actionNames(d));
        out.put("debug", d.isDebug());
        if (d.getBreakpoints() != null) out.put("breakpoints", d.getBreakpoints());
        RunSimulationDto simulation = d.getSimulation();
        if (simulation != null) out.put("simulation", simulationView(simulation));
        if (d.getRestartableStates() != null) out.put("restartableStates", d.getRestartableStates());
        if (d.getExtra() != null && d.getExtra().get("params") != null)
            out.put("params", d.getExtra().get("params"));
        out.put("variables", d.getVariables());
        if (d.getResult() != null) out.put("result", d.getResult());
        out.put(
                "steps",
                d.getSteps().stream().map(step -> stepView(step, fullStep)).collect(Collectors.toList()));
        if (!d.getChildren().isEmpty()) {
            out.put(
                    "children",
                    d.getChildren().stream().map(WorkflowToolSupport::childView).collect(Collectors.toList()));
        }
        if (d.getCost() != null && !d.getCost().isEmpty()) {
            out.put(
                    "cost",
                    d.getCost().stream().map(WorkflowToolSupport::costView).collect(Collectors.toList()));
        }
        return out;
    }

    /** The few fields that answer "did that do anything": for the steering tools. */
    static Map<String, Object> position(RunDetailDto d) {
        RunSummaryDto s = d.getSummary();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", nativeOf(s.getRunId()));
        out.put("status", s.getStatus() == null ? null : s.getStatus().name());
        out.put("state", s.getStep());
        out.put("allowedActions", actionNames(d));
        out.put("debug", d.isDebug());
        if (d.getBreakpoints() != null) out.put("breakpoints", d.getBreakpoints());
        if (d.getSimulation() != null) out.put("simulation", simulationView(d.getSimulation()));
        putIfPresent(out, "errorMessage", d.getErrorMessage());
        return out;
    }

    static List<String> actionNames(RunDetailDto d) {
        List<String> names = new ArrayList<>();
        for (RunAction a : d.getAllowedActions()) names.add(a.name().toLowerCase(java.util.Locale.ROOT));
        names.sort(String::compareTo);
        return names;
    }

    private static Map<String, Object> stepView(RunStepDto step, @Nullable String fullStep) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", step.getName());
        putIfPresent(out, "kind", step.getKind());
        putIfPresent(out, "outcome", step.getOutcome());
        putIfPresent(out, "at", Objects.toString(step.getAt(), null));
        if (step.getDurationMs() != null) out.put("durationMs", step.getDurationMs());
        if (step.isSimulated()) out.put("simulated", true);
        putIfPresent(out, "detail", step.getDetail());
        String output = step.getOutput();
        if (output != null) {
            boolean whole = step.getName().equals(fullStep);
            if (!whole && output.length() > STEP_OUTPUT_CHARS) {
                out.put("output", output.substring(0, STEP_OUTPUT_CHARS));
                out.put("outputCut", true);
            } else {
                out.put("output", output);
                if (step.isOutputTruncated()) out.put("outputCut", true);
            }
        }
        return out;
    }

    private static Map<String, Object> simulationView(RunSimulationDto simulation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", simulation.getState());
        out.put("kind", simulation.getKind());
        out.put("outcomes", simulation.getOutcomes());
        return out;
    }

    private static Map<String, Object> childView(RunChildDto child) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", nativeOf(child.getRunId()));
        putIfPresent(out, "workflow", child.getName());
        putIfPresent(out, "fromState", child.getFromStep());
        if (child.getStatus() != null) out.put("status", child.getStatus().name());
        return out;
    }

    private static Map<String, Object> costView(RunCostDto cost) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", cost.getCurrency());
        out.put("amount", cost.getAmount());
        out.put("unpricedCalls", cost.getUnpricedCalls());
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }

    /** A string argument, trimmed; {@code null} when absent or blank. */
    static @Nullable String text(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** A list-of-strings argument; a bare string counts as a list of one. Absent gives {@code null}. */
    static @Nullable List<String> textList(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        } else if (raw instanceof String s) {
            if (!s.isBlank()) out.add(s.trim());
        } else {
            throw new ToolException("'" + key + "' must be a list of state names");
        }
        return out;
    }
}
