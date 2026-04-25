package de.mhus.vance.brain.tools;

import org.jspecify.annotations.Nullable;

/**
 * Scope handed to a tool on invocation. Tools should treat it as
 * read-only — it is built fresh per call by {@link ToolDispatcher}.
 *
 * <p>{@code projectId}, {@code sessionId} and {@code processId} may be
 * {@code null} for tools invoked outside a think-process (e.g. admin
 * flows). Tools that need them must validate.
 *
 * <p>{@code projectId} is the natural scope for project-affinitive
 * resources (workspace files, exec jobs, future memory indexes); a
 * tool typically prefers it over {@code sessionId}.
 */
public record ToolInvocationContext(
        String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        @Nullable String userId) {
}
