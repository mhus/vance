package de.mhus.vance.toolpack;

import org.jspecify.annotations.Nullable;

/**
 * Scope handed to a tool on invocation. Tools should treat it as
 * read-only — it is built fresh per call by the dispatcher.
 *
 * <p>{@code projectId}, {@code sessionId} and {@code processId} may be
 * {@code null} for tools invoked outside a think-process (e.g. admin
 * flows). Tools that need them must validate.
 *
 * <p>{@code projectId} is the natural scope for project-affinitive
 * resources (workspace files, exec jobs, future memory indexes); a
 * tool typically prefers it over {@code sessionId}.
 *
 * <p>On the foot client side, where there is one user and one session
 * per process, the foot adapter passes a context whose
 * {@code tenantId} reflects the bound tenant and the rest can be
 * empty/null. Tool implementations must therefore treat the optional
 * fields defensively.
 */
public record ToolInvocationContext(
        String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        @Nullable String userId) {
}
