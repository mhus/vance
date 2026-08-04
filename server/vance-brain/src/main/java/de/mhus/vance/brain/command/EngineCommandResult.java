package de.mhus.vance.brain.command;

import de.mhus.vance.api.command.EngineCommandOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of dispatching an {@link EngineCommand}. Handlers return this;
 * the {@link EngineCommandDispatcher} maps unknown verbs and handler
 * failures onto it too. See {@code planning/engine-commands.md} §2.
 *
 * @param outcome classification (OK / UNKNOWN / ERROR)
 * @param message human-readable detail, or {@code null}
 * @param value   optional structured result the handler produced
 */
public record EngineCommandResult(
        EngineCommandOutcome outcome,
        @Nullable String message,
        @Nullable Object value) {

    public static EngineCommandResult ok() {
        return new EngineCommandResult(EngineCommandOutcome.OK, null, null);
    }

    public static EngineCommandResult ok(@Nullable String message, @Nullable Object value) {
        return new EngineCommandResult(EngineCommandOutcome.OK, message, value);
    }

    public static EngineCommandResult unknown(@Nullable String message) {
        return new EngineCommandResult(EngineCommandOutcome.UNKNOWN, message, null);
    }

    public static EngineCommandResult error(@Nullable String message) {
        return new EngineCommandResult(EngineCommandOutcome.ERROR, message, null);
    }
}
