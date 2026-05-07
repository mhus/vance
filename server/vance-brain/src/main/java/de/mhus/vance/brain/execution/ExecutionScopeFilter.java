package de.mhus.vance.brain.execution;

import org.jspecify.annotations.Nullable;

/**
 * Optional filter for {@link ExecutionRegistryService#list(ExecutionScopeFilter)}.
 * Any {@code null} field matches everything; non-null fields require an
 * equal value on the entry. {@link #onlyRunning} narrows further to
 * non-terminal entries.
 */
public record ExecutionScopeFilter(
        @Nullable String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        @Nullable String ownerLabel,
        boolean onlyRunning) {

    public static ExecutionScopeFilter all() {
        return new ExecutionScopeFilter(null, null, null, null, null, false);
    }

    public static ExecutionScopeFilter forProject(String tenantId, String projectId) {
        return new ExecutionScopeFilter(tenantId, projectId, null, null, null, false);
    }

    boolean matches(ExecutionRegistryEntry e) {
        if (tenantId != null && !tenantId.equals(e.tenantId())) return false;
        if (projectId != null && !projectId.equals(e.projectId())) return false;
        if (sessionId != null && !sessionId.equals(e.sessionId())) return false;
        if (processId != null && !processId.equals(e.processId())) return false;
        if (ownerLabel != null && !ownerLabel.equals(e.owner().label())) return false;
        if (onlyRunning && e.status() != ExecutionStatus.RUNNING) return false;
        return true;
    }
}
