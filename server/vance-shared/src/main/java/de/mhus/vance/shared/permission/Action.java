package de.mhus.vance.shared.permission;

/**
 * The verbs a subject may perform on a {@link Resource}.
 *
 * <p>Kept intentionally small. Add a new value only when an existing one
 * cannot honestly describe the operation — e.g. {@link #EXECUTE} for
 * starting/stopping a think process is conceptually different from
 * {@link #WRITE} on its document.
 */
public enum Action {
    /** Read or list. */
    READ,
    /** Modify an existing resource. */
    WRITE,
    /** Create a new resource within a parent scope. */
    CREATE,
    /** Remove a resource. */
    DELETE,
    /**
     * Initiate compute or a long-running activity — spawn a think-process,
     * start/resume a session, kick off a skill/tool. Conceptually heavier
     * than {@link #EXECUTE}: this is the moment quota/resources start being
     * consumed, so it is often gated separately.
     */
    START,
    /**
     * Operate on something already running — pause, resume, steer, send
     * input, stop. Sibling of {@link #START}; the split lets a role allow
     * "control existing process" without granting "spawn new ones".
     */
    EXECUTE,
    /** Configure or grant access — strictly more than WRITE. */
    ADMIN
}
