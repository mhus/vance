package de.mhus.vance.toolpack.feed;

import org.jspecify.annotations.Nullable;

/**
 * Scope that travels with a feed request, built once by the caller and
 * handed down through {@code CentauriService}, {@code FeedSourceFactory}
 * and the {@link FeedSourceInstance}. Mirrors
 * {@link de.mhus.vance.toolpack.research.SearchScope}.
 *
 * <p>{@link #projectId()} is required — feed sources live on the project
 * lifecycle (per-project instance cache, project-scoped cooldowns) and
 * have no fallback scope.
 *
 * <p>{@link #userId()} may be null, and that is a supported state, not a
 * degenerate one: the scheduler and service-account callers have no human
 * behind them. It is <b>not</b> handed to a source directly — the reader
 * pseudonym in {@link FeedActor} is derived from it per instance so no
 * protocol implementation ever sees a user id.
 */
public record FeedScope(
        String tenantId,
        String projectId,
        @Nullable String processId,
        @Nullable String userId) {

    /** Shortcut for tests and anonymous callers (scheduler, digest jobs). */
    public static FeedScope of(String tenantId, String projectId) {
        return new FeedScope(tenantId, projectId, null, null);
    }
}
