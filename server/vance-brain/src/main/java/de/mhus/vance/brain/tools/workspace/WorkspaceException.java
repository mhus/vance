package de.mhus.vance.brain.tools.workspace;

/**
 * Workspace-layer failure with a caller-visible message. Tool dispatch
 * wraps these as {@link de.mhus.vance.brain.tools.ToolException}s so
 * the LLM sees the message in the tool result.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
