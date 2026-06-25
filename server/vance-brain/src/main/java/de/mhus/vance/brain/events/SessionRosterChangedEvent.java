package de.mhus.vance.brain.events;

/**
 * Fired by {@link SessionConnectionRegistry} whenever the participant
 * roster of a session changes — register / unregister / unregisterAll.
 * The roster-broadcaster service listens for this and pushes a
 * {@code session-roster} frame to every remaining connection of the
 * session.
 *
 * <p>See {@code planning/multi-user-sessions.md} §7.
 */
public record SessionRosterChangedEvent(String sessionId) {
}
