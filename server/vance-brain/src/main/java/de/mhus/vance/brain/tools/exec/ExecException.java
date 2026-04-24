package de.mhus.vance.brain.tools.exec;

/** Caller-visible exec-layer failure — wrapped as a {@code ToolException} at the tool edge. */
class ExecException extends RuntimeException {

    ExecException(String message) {
        super(message);
    }

    ExecException(String message, Throwable cause) {
        super(message, cause);
    }
}
