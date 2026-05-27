package de.mhus.vance.shared.memory.evaluation;

import org.jspecify.annotations.Nullable;

/**
 * Pointer from an {@link ExtractedItem} back to a source chat turn.
 *
 * <p>The {@link #turnId()} is the {@code ChatMessageDocument} id (or
 * the analyzer's own message id for items extracted from
 * pre-compacted {@code ARCHIVED_CHAT}s). {@link
 * MemoryEvaluationSanitizer} verifies that each {@code turnId} maps
 * to an existing turn — halluzinated ids cause confidence penalty or
 * item drop.
 *
 * <p>{@link #role()} carries the speaker (user / assistant / tool) so
 * downstream consumers can filter assistant-only evidence that is
 * pure general-knowledge restate (see {@code memory-evaluation-pipeline.md}
 * §3).
 *
 * <p>{@link #snippet()} is the short verbatim cite the analyzer
 * extracted, for audit and recall. May be {@code null} when the item
 * paraphrases across multiple sub-turns.
 */
public record Evidence(
        String turnId,
        EvidenceRole role,
        @Nullable String snippet) {
}
