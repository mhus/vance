package de.mhus.vance.brain.tools;

import org.jspecify.annotations.Nullable;

/**
 * Scope handed to a tool on invocation. Tools should treat it as
 * read-only — it is built fresh per call by {@link ToolDispatcher}.
 *
 * <p>{@code processId} and {@code sessionId} may be {@code null} for
 * tools invoked outside a think-process (e.g. during admin flows). Tools
 * that need them must validate.
 */
public record ToolInvocationContext(
        String tenantId,
        @Nullable String sessionId,
        @Nullable String processId,
        @Nullable String userId) {
}
