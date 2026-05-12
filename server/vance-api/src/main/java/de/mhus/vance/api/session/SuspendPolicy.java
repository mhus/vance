package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What happens once a session has entered {@code SUSPENDED} via a
 * non-{@link SuspendCause#FORCED} cause. {@code FORCED} ignores this
 * and always uses the system-wide {@code FORCED_FLOOR} keep duration.
 *
 * <p>See {@code specification/session-lifecycle.md} §5 + §9.
 *
 * <ul>
 *   <li>{@link #KEEP} — {@code transitionAt} is set to
 *       {@code suspendedAt + suspendKeepDurationMs}; the sweeper
 *       moves the session to {@code ARCHIVED} when that time passes.
 *       Default for user sessions ({@code foot}, {@code web},
 *       {@code mobile}, long-running research).</li>
 *   <li>{@link #CLOSE} — {@code transitionAt} is set to
 *       {@code suspendedAt} (immediately eligible). The sweeper
 *       moves the session to {@code CLOSED}. Used by event-driven
 *       sessions to auto-clean once their job is done.</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum SuspendPolicy {
    KEEP,
    CLOSE
}
