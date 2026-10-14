package de.mhus.vance.brain.wonko;

/**
 * Types of check-queue items (plan §4a.3). Each type has one handler and one
 * defined result schema with an escape outcome and a mandatory reason (§4a.8).
 */
public enum WonkoItemType {
    /** Whole-subject check without references — the v1 check as one item. */
    CHECK,
    /** Split the subject into checkable claims with search terms. */
    DECOMPOSE,
    /** Find the reference passages that apply to one claim. */
    LOCATE,
    /** Compare one claim against its located passages (one call, one chunk). */
    COMPARE,
    /** Which obligations of one reference document does the subject miss? */
    COVERAGE
}
