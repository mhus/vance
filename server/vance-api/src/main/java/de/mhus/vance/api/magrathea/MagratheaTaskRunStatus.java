package de.mhus.vance.api.magrathea;

/**
 * Sub-state of a CLAIMED task indicating what it is currently waiting
 * on. {@code null} means the type-executor is running synchronously
 * in the lane thread. See plan §3.3 and §4.0.
 */
public enum MagratheaTaskRunStatus {
    /** Type-executor is running in-process. */
    RUNNING,
    /** Waiting on a spawned {@code ThinkProcess} to reach a terminal status. §4.1 */
    WAITING_SUBPROCESS,
    /** Waiting on an Inbox-Item answer. §4.4 */
    WAITING_INBOX,
    /** Waiting on a {@code magrathea_timers} row to fire. §4.5 */
    WAITING_TIMER,
    /** Waiting on a sub-{@code MagratheaProcess} to reach a terminal status. §4.7 */
    WAITING_SUBWORKFLOW
}
