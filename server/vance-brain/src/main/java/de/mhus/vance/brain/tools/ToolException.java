package de.mhus.vance.brain.tools;

/**
 * Thrown by {@link Tool#invoke} when a call fails. Carries a message the
 * dispatcher is allowed to surface to the LLM — tools should keep it
 * user-visible (no stack traces, no internal ids).
 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
