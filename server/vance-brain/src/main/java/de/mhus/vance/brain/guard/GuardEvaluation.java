package de.mhus.vance.brain.guard;

import de.mhus.vance.brain.recipe.GuardConfig;
import org.jspecify.annotations.Nullable;

/**
 * Result of {@link CompletionGuardService#evaluate}. {@link #fired()} is
 * {@code true} when a guard's judge fired and a follow-up prompt was
 * injected — the caller uses it to skip its own stop actions.
 */
public record GuardEvaluation(boolean fired, @Nullable GuardConfig guard, @Nullable String reason) {

    private static final GuardEvaluation NOOP = new GuardEvaluation(false, null, null);

    static GuardEvaluation noop() {
        return NOOP;
    }

    static GuardEvaluation passed() {
        return NOOP;
    }

    static GuardEvaluation fired(GuardConfig guard, String reason) {
        return new GuardEvaluation(true, guard, reason);
    }
}
