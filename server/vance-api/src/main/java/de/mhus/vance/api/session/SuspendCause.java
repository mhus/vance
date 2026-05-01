package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Why a session entered {@link SessionStatus#SUSPENDED}. Stamped on
 * the session at suspend-time; drives the computation of {@code deleteAt}
 * (see {@code specification/session-lifecycle.md} §9).
 *
 * <ul>
 *   <li>{@link #IDLE} — idle-detection sweep: session was quiet long
 *       enough to be parked.</li>
 *   <li>{@link #DISCONNECT} — client went away on a session whose
 *       {@code onDisconnect=SUSPEND} policy applies.</li>
 *   <li>{@link #FORCED} — system intervention during running work
 *       (pod-shutdown, lease-loss, admin suspend). Different from the
 *       other two: ignores {@code onSuspend=CLOSE} and uses the
 *       system-wide {@code FORCED_FLOOR} for {@code deleteAt}.</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum SuspendCause {
    IDLE,
    DISCONNECT,
    FORCED
}
