package de.mhus.vance.brain.script;

/**
 * Thrown by {@link ScriptExecutor} when a run fails. Always wraps the
 * underlying cause; the {@link #errorClass()} tells callers what kind
 * of failure they're looking at without parsing the message.
 */
public class ScriptExecutionException extends RuntimeException {

    public enum ErrorClass {
        /** Uncaught JS-side {@code throw} or syntax error. */
        GUEST_EXCEPTION,
        /** Java host method raised an exception during a script call. */
        HOST_EXCEPTION,
        /** Statement-limit or other Polyglot resource limit hit. */
        RESOURCE_EXHAUSTED,
        /** Wall-clock timeout — watchdog cancelled the context. */
        TIMEOUT,
        /** Cancellation requested externally (e.g. interrupt). */
        CANCELLED,
    }

    private final ErrorClass errorClass;

    public ScriptExecutionException(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
