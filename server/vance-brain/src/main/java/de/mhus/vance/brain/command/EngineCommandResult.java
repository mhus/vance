package de.mhus.vance.brain.command;

import de.mhus.vance.api.command.EngineCommandOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of dispatching an {@link EngineCommand}. Handlers return this;
 * the {@link EngineCommandDispatcher} maps unknown verbs and handler
 * failures onto it too. See {@code planning/shooty.md} §4.
 *
 * <p>{@link #deniedByGuard} marks the Shooty COMMAND-point veto: the
 * command failed <b>hard, fail-closed</b> — either the guard script
 * denied it via {@code vance.guard.deny(reason)} or the script itself
 * failed. Callers that run command sequences (the skill
 * activate/deactivate runner) abort the remaining sequence on this
 * marker instead of continuing best-effort.
 *
 * @param outcome classification (OK / UNKNOWN / ERROR)
 * @param message human-readable detail, or {@code null}
 * @param value   optional structured result the handler produced
 * @param deniedByGuard {@code true} when a Shooty guard vetoed this
 *                     command (outcome is then {@code ERROR})
 */
public record EngineCommandResult(
        EngineCommandOutcome outcome,
        @Nullable String message,
        @Nullable Object value,
        boolean deniedByGuard) {

    public static EngineCommandResult ok() {
        return new EngineCommandResult(EngineCommandOutcome.OK, null, null, false);
    }

    public static EngineCommandResult ok(@Nullable String message, @Nullable Object value) {
        return new EngineCommandResult(EngineCommandOutcome.OK, message, value, false);
    }

    public static EngineCommandResult unknown(@Nullable String message) {
        return new EngineCommandResult(EngineCommandOutcome.UNKNOWN, message, null, false);
    }

    public static EngineCommandResult error(@Nullable String message) {
        return new EngineCommandResult(EngineCommandOutcome.ERROR, message, null, false);
    }

    /**
     * A Shooty guard veto — hard, fail-closed denial of the command
     * (guard {@code deny} or guard script failure).
     */
    public static EngineCommandResult guardDenied(@Nullable String message) {
        return new EngineCommandResult(EngineCommandOutcome.ERROR, message, null, true);
    }
}
