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
        /** Script-header parser rejected a malformed tag value
         *  (e.g. {@code @timeout abc}, {@code @statements -1}).
         *  See {@code specification/script-engine.md} §3.5.8. */
        INVALID_HEADER,
        /** Script header declared {@code @requiresTools} entries that
         *  are not in the effective allow set of the executing
         *  context. Raised before evaluation so unrunnable scripts
         *  don't burn LLM tokens. */
        MISSING_CAPABILITY,
    }

    private final ErrorClass errorClass;
    /** Capped tail of the script's console output at the point of
     *  failure (see {@code ConsoleCapture}) — null when the failure
     *  happened before the context was built (pre-eval validation)
     *  or nothing was printed. Lets failure surfaces show what the
     *  script said before it died. */
    private final String consoleOutput;

    public ScriptExecutionException(ErrorClass errorClass, String message, Throwable cause) {
        this(errorClass, message, cause, null);
    }

    /** Convenience for fail-fast paths that have no underlying cause
     *  (e.g. pre-eval validation, malformed header). */
    public ScriptExecutionException(ErrorClass errorClass, String message) {
        this(errorClass, message, null, null);
    }

    public ScriptExecutionException(ErrorClass errorClass, String message, Throwable cause, String consoleOutput) {
        super(message, cause);
        this.errorClass = errorClass;
        this.consoleOutput = consoleOutput;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public String consoleOutput() {
        return consoleOutput == null ? "" : consoleOutput;
    }

    /**
     * Returns a copy carrying the console output — used by the executor
     * at its single eval-failure mapping point, where the capture exists
     * but the classification is already done.
     */
    public ScriptExecutionException withConsoleOutput(String output) {
        return new ScriptExecutionException(errorClass, getMessage(), getCause(), output);
    }
}
