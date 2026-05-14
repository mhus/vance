package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarRunStatus;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Fired by {@link HactarWorkflowService} when a workflow run reaches a
 * terminal {@link HactarRunStatus}. Consumed by
 * {@link HactarSubWorkflowCompletionListener} to advance any parent run
 * waiting on this one as a {@code workflow_task} (plan §4.7, §6.4).
 *
 * <p>{@link #parentHactarProcessId} is set only for sub-workflows
 * started through the parent-linked overload of
 * {@link HactarWorkflowService#start}; pure top-level runs leave it
 * {@code null} and the listener stays silent.
 */
public record WorkflowCompletedEvent(
        String tenantId,
        String projectId,
        String workflowRunId,
        String workflowName,
        HactarRunStatus status,
        @Nullable JsonNode result,
        @Nullable String parentHactarProcessId,
        @Nullable String parentState) {
}
