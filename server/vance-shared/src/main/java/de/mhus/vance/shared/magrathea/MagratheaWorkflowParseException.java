package de.mhus.vance.shared.magrathea;

/** Surfaces parse / validation errors when loading a workflow YAML. */
public class MagratheaWorkflowParseException extends RuntimeException {

    public MagratheaWorkflowParseException(String message) {
        super(message);
    }

    public MagratheaWorkflowParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
