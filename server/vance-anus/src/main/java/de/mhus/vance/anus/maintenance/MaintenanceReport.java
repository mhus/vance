package de.mhus.vance.anus.maintenance;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a maintenance run touched, per entity, plus what it could not account
 * for.
 *
 * <p>One shape for all operations rather than one per verb: the interesting
 * part is always the same table — which handler, how many rows — and a caller
 * that renders "inspect" can render "delete" without knowing there was a
 * difference. The same holds across <em>subjects</em>, which is why this lives
 * outside {@code project.maintenance}: deleting a project and deleting a user
 * are the same run over a different set of handlers, and one report type means
 * one renderer in the shell.
 *
 * @param subject what the run was about — a project name or a user name. The
 *     handler set decides which; the report does not have to know.
 * @param unaccounted collections holding rows for this subject that no handler
 *     claims. Always reported, never acted on: the whole reason to name them is
 *     that nobody knows what they mean.
 */
public record MaintenanceReport(
        String tenantId,
        String subject,
        Operation operation,
        List<EntityResult> entities,
        List<UnaccountedCollection> unaccounted) {

    public enum Operation {
        /** Counted only — nothing was written. */
        INSPECT,
        /** Rows removed. */
        DELETE,
        /** References rewritten to a new name. */
        RENAME
    }

    /**
     * One handler's contribution.
     *
     * @param affected rows counted, deleted or rewritten, depending on the
     *     operation
     * @param note anything the operator has to know that the number does not
     *     say — blobs released alongside, a directory moved, a reference left
     *     in place on purpose
     */
    public record EntityResult(
            String handlerId,
            Set<String> collections,
            long affected,
            @Nullable String note) {

        public static EntityResult of(String handlerId, Set<String> collections, long affected) {
            return new EntityResult(handlerId, collections, affected, null);
        }
    }

    /** A collection with rows for this subject and no handler behind it. */
    public record UnaccountedCollection(String collection, long count) {}

    /** Total across all handlers. */
    public long total() {
        return entities.stream().mapToLong(EntityResult::affected).sum();
    }

    /** True when at least one collection holds rows nobody claims. */
    public boolean hasUnaccounted() {
        return !unaccounted.isEmpty();
    }
}
