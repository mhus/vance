package de.mhus.vance.shared.schema;

import java.util.List;

/**
 * Outcome of one {@link SchemaMigrationService#runPending()} call.
 *
 * @param applied           ids applied by this process, in run order
 * @param declared          number of migrations this build knows
 * @param version           database version after the run — the highest applied
 *                          id, empty when nothing was ever applied
 * @param appliedByOtherPod another pod held the lock and had finished the pending
 *                          work by the time we looked again
 */
public record SchemaMigrationReport(
        List<String> applied,
        int declared,
        String version,
        boolean appliedByOtherPod) {

    /** Nothing to do — the database was already at the required version. */
    public boolean noop() {
        return applied.isEmpty() && !appliedByOtherPod;
    }
}
