package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Spring application event fired by any completion source — synchronous
 * type-executor, ThinkProcess-termination listener, inbox-answer
 * listener, timer-scanner, sub-workflow listener. {@code HactarWorkflowService.onTaskCompleted}
 * is the single consumer; it writes the {@code TaskResultRecord},
 * evaluates transitions, and enqueues the next task.
 *
 * <p>See plan §4.0 (uniform task lifecycle) and §6.4 (completion event bus).
 *
 * @param outcome State-graph outcome — one of {@code success}, {@code failure},
 *                a custom positive outcome ({@code approved}, {@code rejected},
 *                {@code fired}, …) or the lowercased name of a
 *                {@link de.mhus.vance.api.hactar.HactarErrorKind}.
 * @param output Type-executor output. {@code storeAs:} reads from this.
 * @param errorMessage Set on failure outcomes — short human-readable error.
 * @param nextStateOverride Set by {@code CONDITION_TASK} (which decides
 *        the next state inline) to bypass the {@code on:}-block resolver.
 *        {@code null} for every other task type.
 */
public record TaskCompletedEvent(
        String tenantId,
        String projectId,
        String workflowRunId,
        String taskId,
        String stateName,
        HactarTaskType taskType,
        String outcome,
        @Nullable JsonNode output,
        @Nullable String errorMessage,
        long durationMs,
        @Nullable String nextStateOverride) {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
}
