package de.mhus.vance.shared.prak;

import java.util.List;

/**
 * The full result of a single analyzer batch.
 *
 * <p>Each trigger (hot-path, compaction-side-channel, autodream-
 * aggregation, background-consistency) populates only the subset of
 * this schema that is relevant to it — see {@code
 * planning/memory-evaluation-pipeline.md} §5.
 *
 * <p>Empty {@link #items()} and empty {@link #crossItemRelations()}
 * are valid — the analyzer found nothing promotable in the span. The
 * {@link #windowSpan()} is always set for audit.
 */
public record EvaluationOutput(
        WindowSpan windowSpan,
        List<ExtractedItem> items,
        List<CrossItemRelation> crossItemRelations) {

    public static EvaluationOutput empty(WindowSpan windowSpan) {
        return new EvaluationOutput(windowSpan, List.of(), List.of());
    }
}
