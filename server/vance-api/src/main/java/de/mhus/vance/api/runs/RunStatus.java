package de.mhus.vance.api.runs;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Status vocabulary shared by every kind of run, whatever produced it.
 *
 * <p>Six values that a person watching a list actually distinguishes.
 * The underlying runtimes are richer — a Magrathea run has five states, a
 * ThinkProcess seven plus eight close reasons — but most of that
 * difference is about *how* something ended, not about what the viewer
 * has to decide next.
 *
 * <p>{@link #WAITING} deliberately merges "gate open", "checkpoint open",
 * {@code BLOCKED} and {@code IDLE}: for the person looking at the list,
 * "it is waiting for someone" is one situation, and the answer is always
 * to go answer it.
 */
@GenerateTypeScript("runs")
public enum RunStatus {
    /** Work is in progress. */
    RUNNING,
    /** Blocked on a person — an inbox item, a checkpoint, a question. */
    WAITING,
    /** Held on purpose; resumable. */
    PAUSED,
    /** Abort requested, something in flight has not finished yet. */
    STOPPING,
    /** Finished as intended. */
    DONE,
    /** Finished badly. */
    FAILED,
    /** Ended because someone stopped it. */
    STOPPED
}
