package de.mhus.vance.shared.prak;

/**
 * Relation between two items emitted in the <em>same</em> batch.
 *
 * <p>Lets the analyzer say "I saw the user state X early in this span
 * and then correct it to Y later — Y supersedes X" without having to
 * persist both as independent memories. Resolved Java-side before the
 * promotion consumer sees the items.
 */
public enum CrossItemRelationType {

    /** {@code to} replaces {@code from} — only {@code to} should be persisted. */
    SUPERSEDES_WITHIN_BATCH,

    /** {@code to} adds detail to {@code from}; both should be kept and linked. */
    EXTENDS_WITHIN_BATCH
}
