package de.mhus.vance.shared.prak;

/**
 * Relation between two items in the same analyzer batch.
 *
 * <p>{@link #fromItemId()} and {@link #toItemId()} reference
 * {@link ExtractedItem#id()} within the same {@link EvaluationOutput}.
 * Resolution happens before the promotion consumer reads the items.
 */
public record CrossItemRelation(
        String fromItemId,
        String toItemId,
        CrossItemRelationType relation) {
}
