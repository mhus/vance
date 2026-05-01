package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What happens when the session has been idle (no engine in
 * {@code RUNNING}, {@code BLOCKED}, or {@code PAUSED}) for at least
 * {@code idleTimeoutMs}.
 *
 * <p>See {@code specification/session-lifecycle.md} §5 + §7.
 *
 * <ul>
 *   <li>{@link #SUSPEND} — session → {@code SUSPENDED} via the
 *       suspend-cascade. From there, {@code onSuspend} decides whether
 *       it lingers or closes.</li>
 *   <li>{@link #NONE} — idle-sweep is a no-op for this session.
 *       Typical for {@code foot}, where idle just means "user is
 *       thinking".</li>
 * </ul>
 */
@GenerateTypeScript("session")
public enum IdlePolicy {
    SUSPEND,
    NONE
}
