package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.Map;

/**
 * Read-only view a {@link MagratheaTypeExecutor} receives at execution
 * time. Carries the resolved workflow snapshot (parsed from
 * {@code StartRecord.definitionYaml}), the active state spec, run
 * identity, caller params, and the latest replayed variables.
 *
 * <p>Type-executors must not touch the journal directly — the
 * {@link MagratheaTaskExecutor} dispatcher owns all journal writes
 * (TaskStartedRecord, TaskResultRecord, VarRecord, StatusRecord,
 * ResultRecord). Executors return a {@link TaskOutcome}; the dispatcher
 * derives every persistent effect from it.
 */
public record MagratheaTaskContext(
        String tenantId,
        String projectId,
        String workflowRunId,
        String taskId,
        @org.jspecify.annotations.Nullable String startedBy,
        ResolvedMagratheaWorkflow workflow,
        MagratheaStateSpec state,
        Map<String, Object> params,
        Map<String, Object> vars,
        /**
         * Session the run is bound to, or null for a headless run — which
         * gets its system session lazily, per agent-task.
         */
        @org.jspecify.annotations.Nullable String sessionId,
        /**
         * ThinkProcess that owns the run and waits for its result, or null.
         * Presence of this is what lets a task reach a conversation.
         */
        @org.jspecify.annotations.Nullable String ownerProcessId,
        /**
         * The state the run was in before this one, or null at the start. Only read for a
         * {@code FOR_EACH_TASK}, which has to tell the next element from a new loop; the dispatcher fills it in
         * for that type alone.
         */
        @org.jspecify.annotations.Nullable String previousState,
        /**
         * Project the credential that started the run is pinned to, or null. Tasks that call tools build their
         * {@link de.mhus.vance.toolpack.ToolInvocationContext} with it, so the confinement reaches the
         * permission subject.
         */
        @org.jspecify.annotations.Nullable String confinedToProject) {

    /** A context without a previous state — for every type that does not look back. */
    public MagratheaTaskContext(
            String tenantId,
            String projectId,
            String workflowRunId,
            String taskId,
            @org.jspecify.annotations.Nullable String startedBy,
            ResolvedMagratheaWorkflow workflow,
            MagratheaStateSpec state,
            Map<String, Object> params,
            Map<String, Object> vars,
            @org.jspecify.annotations.Nullable String sessionId,
            @org.jspecify.annotations.Nullable String ownerProcessId) {
        this(
                tenantId,
                projectId,
                workflowRunId,
                taskId,
                startedBy,
                workflow,
                state,
                params,
                vars,
                sessionId,
                ownerProcessId,
                null,
                null);
    }

    /** A context for a run without credential confinement but with a previous state. */
    public MagratheaTaskContext(
            String tenantId,
            String projectId,
            String workflowRunId,
            String taskId,
            @org.jspecify.annotations.Nullable String startedBy,
            ResolvedMagratheaWorkflow workflow,
            MagratheaStateSpec state,
            Map<String, Object> params,
            Map<String, Object> vars,
            @org.jspecify.annotations.Nullable String sessionId,
            @org.jspecify.annotations.Nullable String ownerProcessId,
            @org.jspecify.annotations.Nullable String previousState) {
        this(
                tenantId,
                projectId,
                workflowRunId,
                taskId,
                startedBy,
                workflow,
                state,
                params,
                vars,
                sessionId,
                ownerProcessId,
                previousState,
                null);
    }
}
