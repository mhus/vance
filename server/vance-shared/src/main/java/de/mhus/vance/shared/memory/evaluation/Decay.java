package de.mhus.vance.shared.memory.evaluation;

/**
 * TTL hint for how quickly an item's relevance fades without
 * re-observation. Consumed by the span-strength layer (time-down)
 * and by the periodic background-consistency check (refresh-needed
 * detection).
 */
public enum Decay {

    /** Item is timeless ("Codebase uses Java" — until the project switches). */
    NEVER,

    /** Weeks to months ("user works in Berlin office"). */
    SLOW,

    /** Days to weeks ("currently working on feature X"). */
    MEDIUM,

    /** Hours ("blocked on Q from yesterday"). */
    FAST
}
