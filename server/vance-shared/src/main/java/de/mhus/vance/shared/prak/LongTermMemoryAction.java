package de.mhus.vance.shared.prak;

/**
 * What should happen with this item in long-term memory.
 *
 * <p>Orthogonal to {@link AffectsAction} (which controls what happens
 * with <em>existing</em> memory entries). A single item can carry both
 * — e.g. {@link #PROMOTE} + supersede = "new rule replaces old".
 */
public enum LongTermMemoryAction {

    /** Add as a new memory entry without user confirmation. */
    PROMOTE,

    /** Surface as inbox item; user must accept before persistence. */
    INBOX_OFFER,

    /** Do not persist this item at all (importance 0, redundant, pure revocation trigger). */
    SKIP,

    /** Item is a re-observation of an existing memory; reset its decay timer. */
    REFRESH
}
