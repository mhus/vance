package de.mhus.vance.api.magrathea;

/**
 * Lifecycle of a single task row in the {@code magrathea_tasks} queue.
 * See plan §3.3 and §4.0 for the uniform state machine.
 */
public enum MagratheaTaskStatus {
    /** In queue, waiting for a pod to claim. */
    PENDING,
    /** A pod has claimed it, possibly already executing. */
    CLAIMED,
    /** Terminal — {@code TaskResultRecord} written, completion event fired. */
    DONE,
    /** Terminal — execution failed permanently (after retries/reclaims). */
    FAILED
}
