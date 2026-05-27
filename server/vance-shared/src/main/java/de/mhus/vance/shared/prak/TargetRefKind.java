package de.mhus.vance.shared.prak;

/**
 * How a {@link TargetRef} addresses the memory entry it affects.
 *
 * <p>{@link #LABELS} is the bread-and-butter case — the analyzer can't
 * know existing memory ids but can guess at labels with high recall.
 * {@link #MEMORY_ID} is used when the analyzer was explicitly given a
 * memory cluster as input (background-consistency trigger).
 * {@link #PATTERN} is a last-resort regex/keyword fallback.
 */
public enum TargetRefKind {

    /** Direct memory id reference — exact match. */
    MEMORY_ID,

    /** Label intersect — see {@link TargetRef#matchMode()} and {@link TargetRef#minOverlap()}. */
    LABELS,

    /** Pattern (regex / keyword) over memory content — fallback only. */
    PATTERN
}
