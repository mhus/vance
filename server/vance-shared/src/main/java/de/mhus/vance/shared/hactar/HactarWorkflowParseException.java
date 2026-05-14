package de.mhus.vance.shared.hactar;

/** Surfaces parse / validation errors when loading a workflow YAML. */
public class HactarWorkflowParseException extends RuntimeException {

    public HactarWorkflowParseException(String message) {
        super(message);
    }

    public HactarWorkflowParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
