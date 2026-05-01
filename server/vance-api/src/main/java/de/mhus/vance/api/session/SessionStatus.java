package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle state of a session. Five values; orthogonal to the
 * {@code bound}/{@code unbound} state (whether a client connection
 * is currently attached).
 *
 * <p>See {@code specification/session-lifecycle.md} §2 + §4.
 *
 * <ul>
 *   <li>{@link #INIT} — session created, bootstrap (chat-process spawn,
 *       engine start) not yet complete.</li>
 *   <li>{@link #RUNNING} — at least one engine is {@code RUNNING},
 *       or has a non-empty pending-queue with status {@code IDLE/BLOCKED}.
 *       Derived rollup over engine statuses.</li>
 *   <li>{@link #IDLE} — every non-{@code CLOSED} engine is in
 *       {@code IDLE} or {@code SUSPENDED}, or there are no engines.
 *       Derived rollup over engine statuses.</li>
 *   <li>{@link #SUSPENDED} — session is externally halted; carries
 *       {@code suspendCause} and {@code deleteAt} fields on the
 *       persisted document.</li>
 *   <li>{@link #CLOSED} — terminal. All engines terminated, no resume
 *       possible.</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum SessionStatus {
    INIT,
    RUNNING,
    IDLE,
    SUSPENDED,
    CLOSED
}
