package de.mhus.vance.brain.recipe;

/**
 * When a completion guard is evaluated relative to an engine's yield
 * point. See {@code planning/completion-guard.md} §1.
 */
public enum GuardTrigger {

    /** Fire on a natural stop (engine produced its output and would yield). */
    STOP,

    /** Fire on an explicit terminate (e.g. Frankie's {@code _terminate}). */
    TERMINATE,

    /** Fire on either. */
    BOTH;

    public boolean firesOnNaturalStop() {
        return this == STOP || this == BOTH;
    }

    public boolean firesOnTerminate() {
        return this == TERMINATE || this == BOTH;
    }
}
