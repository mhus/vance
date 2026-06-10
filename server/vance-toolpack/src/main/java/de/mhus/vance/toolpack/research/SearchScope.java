package de.mhus.vance.toolpack.research;

import org.jspecify.annotations.Nullable;

/**
 * Scope that travels with a search request. Built once from the
 * {@link de.mhus.vance.toolpack.ToolInvocationContext} in the
 * frontend tool and handed down through {@code ZarniwoopService},
 * {@code SearchProviderFactory} and the {@link SearchProviderInstance}.
 *
 * <p>The Zarniwoop dispatcher rejects any request whose
 * {@link #projectId()} is null or blank — research lives on the
 * project lifecycle (workspace temp-root, project-scoped cooldowns)
 * and has no fallback scope.
 */
public record SearchScope(
        String tenantId,
        String projectId,
        @Nullable String processId,
        @Nullable String userId) {

    /** Shortcut used by tests / admin callers that don't carry a process. */
    public static SearchScope of(String tenantId, String projectId) {
        return new SearchScope(tenantId, projectId, null, null);
    }
}
