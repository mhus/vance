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
 */
public record ScriptResult(
        @Nullable Object value,
        Duration duration) {
}
