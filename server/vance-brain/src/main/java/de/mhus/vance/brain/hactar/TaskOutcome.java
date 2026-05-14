package de.mhus.vance.brain.hactar;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a {@link HactarTypeExecutor} returns to the
 * {@link HactarTaskExecutor} when it finishes synchronously. The
 * dispatcher copies these fields into a {@link TaskCompletedEvent} and
 * publishes it.
 *
 * <p>Type-executors that work asynchronously (subprocess wait, inbox
 * wait, timer wait, sub-workflow wait) return {@code Optional.empty()}
 * from {@link HactarTypeExecutor#execute} instead — completion arrives
 * later via a dedicated listener that fires the event directly.
 *
 * @param outcome State-graph outcome — see {@link TaskCompletedEvent}.
 * @param output Result payload, typed via {@code storeAs:}.
 * @param errorMessage Human-readable error detail when {@link #outcome}
 *        is a failure or error-kind.
 * @param nextStateOverride Set by {@code CONDITION_TASK} to pin the
 *        next state directly; {@code null} for all other types.
 */
public record TaskOutcome(
        String outcome,
        @Nullable JsonNode output,
        @Nullable String errorMessage,
        @Nullable String nextStateOverride) {

    public static TaskOutcome success() {
        return new TaskOutcome(TaskCompletedEvent.OUTCOME_SUCCESS, null, null, null);
    }

    public static TaskOutcome successWith(@Nullable JsonNode output) {
        return new TaskOutcome(TaskCompletedEvent.OUTCOME_SUCCESS, output, null, null);
    }

    public static TaskOutcome chosen(String nextState) {
        return new TaskOutcome(TaskCompletedEvent.OUTCOME_SUCCESS, null, null, nextState);
    }

    public static TaskOutcome failure(String errorMessage) {
        return new TaskOutcome(TaskCompletedEvent.OUTCOME_FAILURE, null, errorMessage, null);
    }
}
