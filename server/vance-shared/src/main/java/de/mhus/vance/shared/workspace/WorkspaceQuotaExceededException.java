package de.mhus.vance.shared.workspace;

/**
 * Thrown when a workspace operation hits the configured hard
 * disk-pressure limit. Tools wrap this as a tool error so the LLM
 * can react (free space, retry, or escalate to the user).
 */
public class WorkspaceQuotaExceededException extends WorkspaceException {

    public WorkspaceQuotaExceededException(String message) {
        super(message);
    }
}
