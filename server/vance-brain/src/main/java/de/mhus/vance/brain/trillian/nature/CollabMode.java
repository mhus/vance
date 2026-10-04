package de.mhus.vance.brain.trillian.nature;

/**
 * How sociable a Trillian's project sessions are (A6). Decided by the
 * Nature per {@code session_open} call — never by the model, which would
 * otherwise be choosing its own noise level.
 *
 * <p>What is <b>not</b> up for debate: sessions are visible (shared) and
 * carry their maker's name; the {@code @ai} addressing works. Only the
 * <em>flow of human messages back into the loop</em> varies — that is the
 * part that costs turns.
 */
public enum CollabMode {

    /**
     * Humans may watch and work along; their messages reach the loop as
     * events. For project companions and consultants.
     */
    JOIN,

    /**
     * Shared and visible, but no flow-back: the loop is not disturbed by
     * chat. For watchdogs and operators. The default.
     */
    WATCH,

    /** Private workspace — no shared session at all. For special cases. */
    SOLO
}
