package de.mhus.vance.brain.magrathea;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a {@link MagratheaTypeExecutor} returns to the
 * {@link MagratheaTaskExecutor} when it finishes synchronously. The
 * dispatcher copies these fields into a {@link TaskCompletedEvent} and
 * publishes it.
 *
 * <p>Type-executors that work asynchronously (subprocess wait, inbox
 * wait, timer wait, sub-workflow wait) return {@code Optional.empty()}
 * from {@link MagratheaTypeExecutor#execute} instead — completion arrives
 * later via a dedicated listener that fires the event directly.
 *
 * @param outcome State-graph outcome — see {@link TaskCompletedEvent}.
 * @param output Result payload, typed via {@code storeAs:}.
 * @param errorMessage Human-readable error detail when {@link #outcome}
 *        is a failure or error-kind.
 * @param nextStateOverride Set by {@code CONDITION_TASK} to pin the
 *        next state directly; {@code null} for all other types.
 * @param vars Variables the dispatcher writes as {@code VarRecord}s, in order, before the next state is
 *        resolved — how a {@code FOR_EACH_TASK} hands the element on to its body. Empty for every other type.
 */
public record TaskOutcome(
        String outcome,
        @Nullable JsonNode output,
        @Nullable String errorMessage,
        @Nullable String nextStateOverride,
        java.util.Map<String, JsonNode> vars) {

    public TaskOutcome(
            String outcome,
            @Nullable JsonNode output,
            @Nullable String errorMessage,
            @Nullable String nextStateOverride) {
        this(outcome, output, errorMessage, nextStateOverride, java.util.Map.of());
    }

    /** An outcome that also sets variables — the loop state saying "next element" or "done". */
    public static TaskOutcome withVars(
            String outcome, @Nullable JsonNode output, java.util.Map<String, JsonNode> vars) {
        return new TaskOutcome(outcome, output, null, null, vars);
    }

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

    /**
     * The state needs a {@code RunCapability} this run was not started with,
     * and its author said what to do about it with
     * {@code catch: { capability_missing: … }}.
     *
     * <p>The outcome name is the lower-case form of
     * {@link de.mhus.vance.api.magrathea.MagratheaErrorKind#CAPABILITY_MISSING},
     * which is how {@code catch:} routing reads an outcome back into an
     * error kind.
     */
    public static TaskOutcome capabilityMissing(String errorMessage) {
        return new TaskOutcome(TaskCompletedEvent.OUTCOME_CAPABILITY_MISSING, null, errorMessage, null);
    }
}
