package de.mhus.vance.brain.prak;

/**
 * What changed during sanitization, for telemetry and audit.
 *
 * <p>All counts are post-sanitization deltas from the raw analyzer
 * output. {@code evidenceCoverage} is over substantial messages —
 * {@code 0.0} if the sanitize context reported {@code 0} substantial
 * messages (degenerate / not meaningful).
 */
public record SanitizeMetrics(
        int rawItemCount,
        int finalItemCount,
        int droppedNoEvidence,
        int droppedLowConfidence,
        int droppedBySupersedeWithinBatch,
        int duplicatesMerged,
        int confidencePenalised,
        boolean hardCapTriggered,
        double evidenceCoverage,
        boolean lowCoverage) {
}
