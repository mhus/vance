package de.mhus.vance.toolpack;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link Tool#invoke} when a call fails. Carries a message
 * the dispatcher is allowed to surface to the LLM — tools should keep
 * it user-visible (no stack traces, no internal ids).
 *
 * <p>May additionally carry the failing tool's
 * {@link Tool#troubleshootingHint()}. The hint is a <em>separate</em>
 * field on purpose: it used to be prepended to the message as
 * {@code "hint: … -- <message>"}, which put advice in front of the
 * failure and left models reading the whole result as a suggestion
 * rather than as "your call did not happen". Renderers put the failure
 * first and the hint in its own field.
 */
public class ToolException extends RuntimeException {

    private final @Nullable String hint;

    public ToolException(String message) {
        this(message, null, null);
    }

    public ToolException(String message, @Nullable Throwable cause) {
        this(message, null, cause);
    }

    public ToolException(String message, @Nullable String hint, @Nullable Throwable cause) {
        super(message, cause);
        this.hint = hint == null || hint.isBlank() ? null : hint;
    }

    /** Recovery advice for the failing tool, or {@code null} if it has none. */
    public @Nullable String getHint() {
        return hint;
    }
}
