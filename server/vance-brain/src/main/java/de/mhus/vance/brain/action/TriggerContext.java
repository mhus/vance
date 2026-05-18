package de.mhus.vance.brain.action;

import org.jspecify.annotations.Nullable;

/**
 * Scope/identity information the trigger surfaces to the executor. All
 * triggers carry tenant + project; everything else is optional and
 * filled per surface.
 *
 * <p>{@code correlationId} is the same id that goes into the event-log
 * row for this run — the executor uses it for downstream logs and
 * (where relevant) writes it onto the spawned Process / Workflow-Run.
 *
 * <p>{@code sourceTag} is the trigger-specific tag (e.g.
 * {@code "scheduler:morning-briefing"}, {@code "event:github-pr"},
 * {@code "workflow:<runId>:plan"}); the executor logs it but does not
 * interpret it.
 */
public record TriggerContext(
        String tenantId,
        String projectId,
        @Nullable String resolvedRunAs,
        @Nullable String correlationId,
        @Nullable String sourceTag,
        @Nullable String parentSessionId,
        @Nullable String parentProcessId) {

    public TriggerContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("TriggerContext.tenantId must be non-blank");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("TriggerContext.projectId must be non-blank");
        }
    }
}
