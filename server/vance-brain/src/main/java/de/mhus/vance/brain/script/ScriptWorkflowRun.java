package de.mhus.vance.brain.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The run a script is executing <em>inside</em> — surfaced to JavaScript
 * as {@code vance.workflow.current}. Non-null only for a Magrathea
 * {@code script_task}; every other script run (Cortex, skill, guard,
 * scheduler) leaves it unset.
 *
 * <p>Read-only by construction. A task already receives the values the
 * plan author substituted into its {@code params:} block, but it could
 * not see the run it belongs to, nor a variable the author forgot to
 * thread through — a missing {@code ${state.x}} resolves to the empty
 * string, so the omission surfaced as bad data rather than as an error.
 * This closes that gap on the read side only.
 *
 * <p><b>Why there is no setter.</b> Variables are a projection of the
 * journal, and {@code MagratheaTaskContext} states the rule the whole
 * subsystem rests on: type-executors must not touch the journal — the
 * {@code MagratheaTaskExecutor} dispatcher derives every persistent
 * effect from the returned {@code TaskOutcome}. An out-of-band write
 * from a script would need its own journal record and would break
 * replay. The write path stays the script's return value plus
 * {@code storeAs:}.
 *
 * @param runId        the {@code workflowRunId} this task belongs to
 * @param workflowName definition name (file stem for path-started runs)
 * @param state        name of the state currently executing — this task
 * @param taskId       id of the {@code magrathea_tasks} row being run
 * @param startedBy    who started the run, or {@code null} when headless
 * @param params       caller params of the run, after defaulting
 * @param vars         variables replayed from the journal so far
 */
public record ScriptWorkflowRun(
        String runId,
        String workflowName,
        String state,
        String taskId,
        @Nullable String startedBy,
        Map<String, Object> params,
        Map<String, Object> vars) {

    public ScriptWorkflowRun {
        params = copy(params);
        vars = copy(vars);
    }

    private static Map<String, Object> copy(@Nullable Map<String, Object> src) {
        return src == null || src.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(src));
    }

    /**
     * The JS-facing shape of {@code vance.workflow.current}. A plain map
     * rather than the record itself, so scripts read fields
     * ({@code current.runId}) instead of calling record accessors
     * ({@code current.runId()}) — the same convention every other value
     * crossing this boundary follows.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("workflowName", workflowName);
        out.put("state", state);
        out.put("taskId", taskId);
        out.put("startedBy", startedBy);
        out.put("params", params);
        out.put("vars", vars);
        return Collections.unmodifiableMap(out);
    }
}
