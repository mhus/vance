package de.mhus.vance.brain.hactar;

import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import java.util.Map;

/**
 * Read-only view a {@link HactarTypeExecutor} receives at execution
 * time. Carries the resolved workflow snapshot (parsed from
 * {@code StartRecord.definitionYaml}), the active state spec, run
 * identity, caller params, and the latest replayed variables.
 *
 * <p>Type-executors must not touch the journal directly — the
 * {@link HactarTaskExecutor} dispatcher owns all journal writes
 * (TaskStartedRecord, TaskResultRecord, VarRecord, StatusRecord,
 * ResultRecord). Executors return a {@link TaskOutcome}; the dispatcher
 * derives every persistent effect from it.
 */
public record HactarTaskContext(
        String tenantId,
        String projectId,
        String workflowRunId,
        String taskId,
        @org.jspecify.annotations.Nullable String startedBy,
        ResolvedHactarWorkflow workflow,
        HactarStateSpec state,
        Map<String, Object> params,
        Map<String, Object> vars) {
}
