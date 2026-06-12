package de.mhus.vance.brain.execution;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One row in the cross-side execution index. Immutable snapshot — the
 * registry replaces entries by id when the owner reports a change.
 *
 * <p>{@code labels} are per-instance metadata used for cross-cutting
 * filters (Cortex document linkage, language, source). Convention keys
 * live in {@code planning/script-document-api.md} §4.5; values stay
 * in-memory and never reach Micrometer.
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
        @Nullable String stderrPath,
        Map<String, String> labels) {

    public ExecutionRegistryEntry {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /** Convenience constructor for callers that don't carry labels. */
    public ExecutionRegistryEntry(
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
        this(executionId, owner, tenantId, projectId, sessionId, processId,
                command, dirName, startedAt, lastOutputAt, endedAt,
                status, exitCode, stdoutPath, stderrPath, Map.of());
    }

    public ExecutionRegistryEntry withProgress(
            Instant lastOutputAt, ExecutionStatus status,
            @Nullable Integer exitCode, @Nullable Instant endedAt) {
        return new ExecutionRegistryEntry(
                executionId, owner, tenantId, projectId, sessionId, processId,
                command, dirName, startedAt, lastOutputAt, endedAt,
                status, exitCode, stdoutPath, stderrPath, labels);
    }

    public ExecutionRegistryEntry withProcessId(String newProcessId) {
        return new ExecutionRegistryEntry(
                executionId, owner, tenantId, projectId, sessionId, newProcessId,
                command, dirName, startedAt, lastOutputAt, endedAt,
                status, exitCode, stdoutPath, stderrPath, labels);
    }
}
