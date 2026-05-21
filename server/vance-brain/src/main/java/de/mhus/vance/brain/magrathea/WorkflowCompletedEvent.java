package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Fired by {@link MagratheaWorkflowService} when a workflow run reaches a
 * terminal {@link MagratheaRunStatus}. Consumed by
 * {@link MagratheaSubWorkflowCompletionListener} to advance any parent run
 * waiting on this one as a {@code workflow_task} (plan §4.7, §6.4).
 *
 * <p>{@link #parentMagratheaProcessId} is set only for sub-workflows
 * started through the parent-linked overload of
 * {@link MagratheaWorkflowService#start}; pure top-level runs leave it
 * {@code null} and the listener stays silent.
 */
public record WorkflowCompletedEvent(
        String tenantId,
        String projectId,
        String workflowRunId,
        String workflowName,
        MagratheaRunStatus status,
        @Nullable JsonNode result,
        @Nullable String parentMagratheaProcessId,
        @Nullable String parentState) {
}
