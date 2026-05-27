package de.mhus.vance.brain.prak;

import java.util.List;

/**
 * Outcome of a {@link PrakPromotionService#promote} call.
 *
 * <p>Counts are partitioned by the effective action <em>after</em> the
 * default-action resolver runs (so a {@code PROMOTE} on an
 * {@code INSTRUCTION} that got downgraded to {@code INBOX_OFFER}
 * counts as the latter). {@link #persistedMemoryIds()} are the ids of
 * the newly written {@link de.mhus.vance.shared.memory.MemoryDocument}s
 * — caller can use them for audit logging.
 *
 * <p>{@link #affectsDeferred()} counts the {@code affectsExisting}
 * entries that the current implementation does not yet apply (label
 * lookup arrives in a later phase). They're logged as telemetry so
 * the gap is visible.
 */
public record PromotionResult(
        int promoted,
        int inboxOffered,
        int skipped,
        int refreshed,
        int affectsResolved,
        int affectsDeferred,
        List<String> persistedMemoryIds) {

    public static PromotionResult empty() {
        return new PromotionResult(0, 0, 0, 0, 0, 0, List.of());
    }
}
