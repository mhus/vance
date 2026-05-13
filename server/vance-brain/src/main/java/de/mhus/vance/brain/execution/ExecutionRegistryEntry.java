package de.mhus.vance.brain.execution;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row in the cross-side execution index. Immutable snapshot — the
 * registry replaces entries by id when the owner reports a change.
 */
public record ExecutionRegistryEntry(
        String executionId,
        ExecutionOwner owner,
        @Nullable String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        String command,
        @Nullable String dirName,
        Instant startedAt,
        Instant lastOutputAt,
        @Nullable Instant endedAt,
        ExecutionStatus status,
        @Nullable Integer exitCode,
        @Nullable String stdoutPath,
        @Nullable String stderrPath) {

    public ExecutionRegistryEntry withProgress(
            Instant lastOutputAt, ExecutionStatus status,
            @Nullable Integer exitCode, @Nullable Instant endedAt) {
        return new ExecutionRegistryEntry(
                executionId, owner, tenantId, projectId, sessionId, processId,
                command, dirName, startedAt, lastOutputAt, endedAt,
                status, exitCode, stdoutPath, stderrPath);
    }

    public ExecutionRegistryEntry withProcessId(String newProcessId) {
        return new ExecutionRegistryEntry(
                executionId, owner, tenantId, projectId, sessionId, newProcessId,
                command, dirName, startedAt, lastOutputAt, endedAt,
                status, exitCode, stdoutPath, stderrPath);
    }
}
