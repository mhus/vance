package de.mhus.vance.api.marvin;

/**
 * Action the worker selects in the POST_CHILDREN phase. See
 * {@code specification/marvin-engine.md} §4.3.
 */
public enum PostChildrenAction {
    /** Children's results suffice — jump to CONCLUDE. */
    PROCEED_TO_CONCLUDE,

    /**
     * Further decomposition needed. Only allowed when current
     * tree-depth &lt; {@code maxTreeDepth}; else the engine forces
     * PROCEED_TO_CONCLUDE.
     */
    NEEDS_SUBTASKS,

    /** Terminal — node FAILED. */
    BLOCKED_BY_PROBLEM
}
