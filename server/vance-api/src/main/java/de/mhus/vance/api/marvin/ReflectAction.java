package de.mhus.vance.api.marvin;

/**
 * Action the worker selects in the REFLECT phase. See
 * {@code specification/marvin-engine.md} §4.2.
 */
public enum ReflectAction {
    /** Another sub-process call needed. Capped at 3 per node. */
    CALL_RECIPE,

    /** Sufficient material gathered — jump to CONCLUDE. */
    PROCEED_TO_CONCLUDE,

    /** Decompose into children; this node parks in WAITING. */
    NEEDS_SUBTASKS,

    /** Ask the user; spawn USER_INPUT child. */
    NEEDS_USER_INPUT,

    /** Terminal — node FAILED. */
    BLOCKED_BY_PROBLEM
}
