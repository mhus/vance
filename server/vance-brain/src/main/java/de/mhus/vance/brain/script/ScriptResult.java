package de.mhus.vance.brain.script;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one successful {@link ScriptExecutor#run} call.
 *
 * <p>{@code value} is the script's return value, mapped from the
 * Polyglot {@code Value} into a JSON-friendly Java object — primitives
 * stay primitives, JS objects become {@link java.util.Map}, JS arrays
 * become {@link java.util.List}. {@code null} when the script returns
 * nothing.
 *
 * <p>{@code consoleOutput} is the capped tail of the script's
 * {@code console.log}/{@code console.error} output (see
 * {@code ConsoleCapture}) — empty string when the script printed
 * nothing. Before the capture existed, console output went to the JVM's
 * stdout, invisible to every caller surface.
 */
public record ScriptResult(@Nullable Object value, Duration duration, String consoleOutput) {

    public ScriptResult {
        if (consoleOutput == null) {
            consoleOutput = "";
        }
        if (duration == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
    }

    /**
     * Historical 2-arg shape — no console capture (kept for call sites
     * and harnesses that don't care about output).
     */
    public ScriptResult(@Nullable Object value, Duration duration) {
        this(value, duration, "");
    }
}
