package de.mhus.vance.brain.memory;

/**
 * Compaction aggression. Returned by
 * {@code CompactionTriggerService.evaluate(...)} and consumed by
 * {@code MemoryCompactionService.compact(...)} to decide which
 * messages survive verbatim vs. get folded into an
 * {@code ARCHIVED_CHAT} summary.
 *
 * <p>Three tiers — each picks a different anchor + a different
 * strength-rule from {@code planning/memory-evaluation-pipeline.md}
 * §6.1:
 *
 * <ul>
 *   <li>{@link #NONE} — no compaction needed (estimated tokens under
 *       the soft threshold).</li>
 *   <li>{@link #SOFT} — gentle. Drops only WEAK + unrated-trivial
 *       (ack / self-narration). NORMAL / STRONG / PINNED all stay
 *       verbatim. Large anchor (last 10).</li>
 *   <li>{@link #HARD} — context is getting tight. Drops WEAK + old
 *       NORMAL + unrated-trivial. STRONG and PINNED stay. Smaller
 *       anchor (last 5).</li>
 *   <li>{@link #EMERGENCY} — context is about to break. Keeps only
 *       PINNED + the last 3 verbatim; everything else is compacted
 *       regardless of strength. Last resort.</li>
 * </ul>
 *
 * <p>Tier-thresholds are configured via {@code PrakProperties.
 * compactionSoftThreshold / compactionHardThreshold /
 * compactionEmergencyThreshold} as fractions of the model's context
 * window.
 */
public enum CompactionMode {
    NONE,
    SOFT,
    HARD,
    EMERGENCY
}
