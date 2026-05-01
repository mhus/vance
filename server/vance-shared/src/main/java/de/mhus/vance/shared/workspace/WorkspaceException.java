package de.mhus.vance.shared.workspace;

/**
 * Workspace-layer failure with a caller-visible message. Tool dispatch
 * is expected to surface the message in the tool result so the LLM sees
 * it.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
