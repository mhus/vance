package de.mhus.vance.api.magrathea;

/**
 * Lifecycle of a single task row in the {@code magrathea_tasks} queue.
 * See plan §3.3 and §4.0 for the uniform state machine.
 */
public enum MagratheaTaskStatus {
    /** In queue, waiting for a pod to claim. */
    PENDING,
    /**
     * Queued but held back because its run is paused. Not claimable —
     * the claimer only looks for {@link #PENDING}, so a held task is
     * invisible to it without the scan having to know about run status.
     * Resuming puts it back to {@link #PENDING}.
     */
    HELD,
    /** A pod has claimed it, possibly already executing. */
    CLAIMED,
    /** Terminal — {@code TaskResultRecord} written, completion event fired. */
    DONE,
    /** Terminal — execution failed permanently (after retries/reclaims). */
    FAILED
}
