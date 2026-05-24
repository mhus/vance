package de.mhus.vance.api.marvin;

/**
 * Action the worker selects in the SCOPE phase. See
 * {@code specification/marvin-engine.md} §4.1.
 */
public enum ScopeAction {
    /** Spawn a sub-process synchronously; reply lands in REFLECT. */
    CALL_RECIPE,

    /** Worker can answer directly — jump to CONCLUDE. */
    PROCEED_TO_CONCLUDE,

    /** Spawn child WORKER nodes; this node parks in WAITING. */
    NEEDS_SUBTASKS,

    /** Ask the user via the inbox; spawn USER_INPUT child. */
    NEEDS_USER_INPUT,

    /** Terminal — node FAILED. */
    BLOCKED_BY_PROBLEM
}
