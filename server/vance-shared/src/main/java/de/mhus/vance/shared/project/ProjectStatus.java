package de.mhus.vance.shared.project;

/**
 * The <em>desired</em> state of a {@link ProjectDocument} — what somebody
 * (an operator, a user, a policy) wants, not what a pod is doing right now.
 * What a pod is doing is {@link ProjectActualState}.
 *
 * <p>The desired state survives crashes and restarts; that is its point. A pod
 * going down, a rolling restart or a lost lease never rewrites it.
 *
 * <pre>
 *   INIT ──bring──► RUNNING ──suspend──► SUSPENDED
 *                      │  ▲                  │
 *                      │  └──── bring ───────┤
 *                   idle│                    │
 *                      ▼                     │
 *                     IDLE ───── bring ──────┘ (wakes on access)
 *
 *   any ──close──► CLOSED   (terminal; reopen goes back to INIT)
 * </pre>
 *
 * <p>Who may undo a stop is the difference between {@code IDLE} and
 * {@code SUSPENDED}: {@code IDLE} is the system's own decision ("nobody
 * needed this for a while") and any access wakes the project; {@code SUSPENDED}
 * is somebody's explicit decision and implicit access leaves it alone — only
 * an explicit start (a session create, an admin resume) brings it back.
 */
public enum ProjectStatus {

    /** Newly created, never brought. Wants to run. */
    INIT,

    /** Should be live somewhere. Which pod runs it is the lease, not this value. */
    RUNNING,

    /**
     * Stopped by the system because nobody used the project for a while.
     * Engines are down, the workspace folder is untouched. Wakes on any access.
     */
    IDLE,

    /**
     * Stopped on purpose; engines are down, the workspace folder is untouched.
     * Resumable by an explicit start, never by implicit access.
     */
    SUSPENDED,

    /** Terminal — assets retained per policy, no further serving. */
    CLOSED
}
