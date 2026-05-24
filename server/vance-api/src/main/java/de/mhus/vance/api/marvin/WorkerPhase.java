package de.mhus.vance.api.marvin;

/**
 * Five phases of a Marvin WORKER node's lifecycle. The state-machine
 * walks SCOPE → optionally CALL_RECIPE/REFLECT loop → optionally
 * POST_CHILDREN → CONCLUDE → VALIDATE → terminal DONE/FAILED.
 *
 * <p>See {@code specification/marvin-engine.md} for the full
 * transition diagram and caps.
 */
public enum WorkerPhase {

    /** Initial decision: act now, call a recipe, decompose, ask user, give up. */
    SCOPE,

    /**
     * Post-CALL_RECIPE evaluation. Up to 3 iterations per node;
     * each iteration may trigger another CALL_RECIPE, terminate
     * via NEEDS_SUBTASKS / NEEDS_USER_INPUT / BLOCKED_BY_PROBLEM,
     * or PROCEED_TO_CONCLUDE.
     */
    REFLECT,

    /**
     * Children-fanout synthesis. Reached after this node's
     * NEEDS_SUBTASKS-spawned children have ALL terminated.
     * Decides whether to PROCEED_TO_CONCLUDE, decompose
     * further (bounded by tree-depth), or BLOCKED_BY_PROBLEM.
     */
    POST_CHILDREN,

    /** Synthesis phase. Produces the candidate final result. */
    CONCLUDE,

    /**
     * Critical validation of the candidate result. Up to 2
     * iterations; can route back to CONCLUDE (RETRY_CONCLUDE),
     * back to REFLECT (NEED_MORE_DATA), or terminate.
     */
    VALIDATE
}
