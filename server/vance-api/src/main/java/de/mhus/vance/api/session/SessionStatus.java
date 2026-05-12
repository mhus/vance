package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle state of a session. Six values; orthogonal to the
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
 *       {@code suspendCause} and {@code transitionAt} fields on the
 *       persisted document. Transient: the sweeper moves it on to
 *       {@code ARCHIVED} or {@code CLOSED} once {@code transitionAt}
 *       passes.</li>
 *   <li>{@link #ARCHIVED} — long-term storage. All engines closed
 *       ({@code closeReason=ARCHIVED}), pod-lease released, pending
 *       queues drained. Conversation history and user metadata
 *       remain and stay searchable. Re-entered only via an explicit
 *       user {@code reactivate} call — no auto-wakeup. UI default
 *       filter blends this out.</li>
 *   <li>{@link #CLOSED} — terminal. All engines terminated, no resume
 *       possible. Eligible for hard-delete. Reached only via (a)
 *       explicit user delete from {@code ARCHIVED}, (b) the
 *       {@code onSuspend=CLOSE} sweeper path (daemon / event-driven),
 *       or (c) abandoned-detection.</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum SessionStatus {
    INIT,
    RUNNING,
    IDLE,
    SUSPENDED,
    ARCHIVED,
    CLOSED
}
