package de.mhus.vance.exec;

/** Caller-visible exec-layer failure — wrapped as a {@code ToolException} at the tool edge. */
public class ExecException extends RuntimeException {

    public ExecException(String message) {
        super(message);
    }

    public ExecException(String message, Throwable cause) {
        super(message, cause);
    }
}
