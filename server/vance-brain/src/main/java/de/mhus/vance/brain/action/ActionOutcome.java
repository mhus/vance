package de.mhus.vance.brain.action;

/**
 * Result class for {@link ActionResult}. Rich enough that Hactar can
 * map 1:1 onto its task-outcome vocabulary; coarse enough that callers
 * who only care about ok/not-ok (scheduler event-log, REST status) can
 * collapse {@link #isFailure()} into a single bit.
 *
 * <p>The mapping rules for script returns live in
 * {@code planning/trigger-actions.md} §5.3.
 */
public enum ActionOutcome {

    /** Sync: action completed successfully. */
    SUCCESS,

    /** Sync: domain-level failure (e.g. script returned {@code success:false}, user exception). */
    BUSINESS_ERROR,

    /** Sync: system-level failure (sandbox crash, IO error, script-not-found). */
    TECHNICAL_ERROR,

    /** Sync: wall-clock timeout exceeded before completion. */
    TIMEOUT,

    /** Sync: caller did not have permission to execute the action. */
    PERMISSION_ERROR,

    /** Sync/async: cancelled before completion (overlap-cancel, manual stop). */
    CANCELLED,

    /**
     * Async: action spawned a Process / Workflow that will report its
     * own terminal state later. {@link ActionResult#spawnedId()} carries
     * the id to track.
     */
    SCHEDULED;

    /** {@code true} for any outcome other than {@link #SUCCESS} and {@link #SCHEDULED}. */
    public boolean isFailure() {
        return this != SUCCESS && this != SCHEDULED;
    }
}
