package de.mhus.vance.api.marvin;

/**
 * Lifecycle states for a {@link MarvinNodeDocument MarvinNode}.
 * See {@code specification/marvin-engine.md} §3 for the transition
 * table.
 */
public enum NodeStatus {

    /** Node exists but has not been started yet — Marvin's frontier. */
    PENDING,

    /** Node is currently executing — for WORKER, the worker process
     *  has been spawned and is running. */
    RUNNING,

    /** Node is waiting for an external event (user input via inbox,
     *  or a worker process that has been parked). */
    WAITING,

    /** Node has completed successfully. */
    DONE,

    /** Node failed — worker went STALE/STOPPED, user answered
     *  UNDECIDABLE, or the recipe-spawn raised an error. */
    FAILED,

    /** Marvin or the user decided to skip this node — re-planning,
     *  no longer relevant, etc. */
    SKIPPED
}
