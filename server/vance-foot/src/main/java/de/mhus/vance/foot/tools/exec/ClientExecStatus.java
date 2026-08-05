package de.mhus.vance.foot.tools.exec;

/**
 * Lifecycle state of a client-side exec job. Top-level and public
 * because it travels out of this package inside {@link ClientExecStat}
 * — the job object itself stays internal.
 */
public enum ClientExecStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    KILLED
}
