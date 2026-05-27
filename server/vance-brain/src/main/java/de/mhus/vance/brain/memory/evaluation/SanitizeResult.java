package de.mhus.vance.brain.memory.evaluation;

import de.mhus.vance.shared.memory.evaluation.EvaluationOutput;

/**
 * What the sanitizer hands back: the cleaned-up evaluation output and
 * a metrics record describing what it changed. Metrics feed both
 * Micrometer counters and the run-metadata audit collection (planned
 * for §4c.9).
 */
public record SanitizeResult(
        EvaluationOutput output,
        SanitizeMetrics metrics) {
}
