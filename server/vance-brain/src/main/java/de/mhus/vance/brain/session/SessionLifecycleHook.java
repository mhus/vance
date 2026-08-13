package de.mhus.vance.brain.session;

import de.mhus.vance.shared.session.SessionDocument;

/**
 * SPI for subsystems that own something attached to a session and must
 * follow its lifetime.
 *
 * <p>{@code SessionLifecycleService} cleans a session's own rows
 * thoroughly — chat messages, processes, memories, group membership. It
 * knows nothing about things hanging off a session elsewhere, and a
 * subsystem that keeps such a thing has no reliable moment to react.
 * Trillian is the case that surfaced this: its worker lives in a second,
 * headless session, and the only signal it had was a process status
 * change. Every path that ends a session without moving exactly that
 * process — deleting an already-closed session, for instance — bypassed
 * the cleanup in silence.
 *
 * <p><b>Lifetime, not steering.</b> Archive, reactivate and delete are
 * lifetime transitions and belong here. Pause, halt and suspend are
 * steering: they stop at the session boundary on purpose, because a
 * headless worker is meant to keep running while nobody is watching.
 * See {@code specification/public/trillian-engine.md} §6a.
 *
 * <p>Implementations are Spring beans, must be idempotent (a session
 * that is closed and later deleted runs two of these), and must not
 * throw — the caller logs and carries on, since a failing hook may not
 * block the transition the user asked for.
 */
public interface SessionLifecycleHook {

    /**
     * The session was archived — put away, not thrown away. Anything
     * that follows it should be archived too, and anything that would
     * make a later reactivation impossible must be preserved.
     */
    default void onSessionArchived(SessionDocument session) {
    }

    /**
     * The session is being reactivated. Called <b>before</b> the fresh
     * chat-process is spawned, so an implementation can prepare state
     * the spawn will pick up.
     */
    default void onSessionUnarchived(SessionDocument session) {
    }

    /**
     * The session is being hard-deleted. Called <b>before</b> its rows
     * are removed, while its processes and their {@code engineParams}
     * can still be read — that is usually where the link to whatever
     * else needs deleting lives.
     */
    default void onSessionDeleted(SessionDocument session) {
    }
}
