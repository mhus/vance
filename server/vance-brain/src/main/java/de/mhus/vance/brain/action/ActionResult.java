package de.mhus.vance.brain.action;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of an {@link ActionExecutor#execute(ActionInvocation) execute()}
 * call.
 *
 * <ul>
 *   <li>{@link #outcome()} — see {@link ActionOutcome} for the full vocabulary.
 *   <li>{@link #spawnedId()} — set when {@link #outcome()} is
 *       {@link ActionOutcome#SCHEDULED}; holds the Process id (Recipe)
 *       or Workflow-Run id (Workflow). {@code null} for sync outcomes.
 *   <li>{@link #output()} — for sync outcomes: the structured result
 *       (script return mapped per §5.3). For async outcomes:
 *       {@code null}; the spawned entity tracks its own output.
 *   <li>{@link #errorMessage()} — short, log-friendly error string for
 *       failure outcomes. {@code null} on success.
 * </ul>
 */
public record ActionResult(
        ActionOutcome outcome,
        @Nullable String spawnedId,
        @Nullable Map<String, Object> output,
        @Nullable String errorMessage) {

    public ActionResult {
        if (outcome == null) {
            throw new IllegalArgumentException("ActionResult.outcome must not be null");
        }
    }

    // ──────────────────── Factory helpers ────────────────────

    /** Sync success with structured output. */
    public static ActionResult success(@Nullable Map<String, Object> output) {
        return new ActionResult(ActionOutcome.SUCCESS, null, output, null);
    }

    /** Async spawn — the spawned entity tracks completion. */
    public static ActionResult scheduled(String spawnedId) {
        if (spawnedId == null || spawnedId.isBlank()) {
            throw new IllegalArgumentException("scheduled() requires a non-blank spawnedId");
        }
        return new ActionResult(ActionOutcome.SCHEDULED, spawnedId, null, null);
    }

    /** Sync failure with explicit outcome class. */
    public static ActionResult failure(ActionOutcome outcome,
                                       String errorMessage,
                                       @Nullable Map<String, Object> output) {
        if (outcome == null || !outcome.isFailure()) {
            throw new IllegalArgumentException(
                    "failure() requires a failure outcome, got " + outcome);
        }
        return new ActionResult(outcome, null, output, errorMessage);
    }
}
