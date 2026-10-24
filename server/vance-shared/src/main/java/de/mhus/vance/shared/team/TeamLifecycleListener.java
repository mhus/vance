package de.mhus.vance.shared.team;

/**
 * Notified when a team comes into existence or goes away — the team twin of
 * {@code UserLifecycleListener}.
 *
 * <p>Role assignments key on the team <em>name</em>, not on the Mongo id, so
 * they survive the team document unless somebody removes them. A name comes
 * back, and a new team under it would silently inherit project roles nobody
 * granted it — together with every member it gets.
 *
 * <p>Same contract as the user side: {@link #onTeamDeleted} runs before the
 * document is removed and a throw aborts the delete; {@link #onTeamCreated}
 * runs after the insert, is best-effort, and a throw is logged.
 */
public interface TeamLifecycleListener {

    /** A new team exists. Best-effort: an exception is logged, the create stands. */
    default void onTeamCreated(TeamDocument team) {
        // nothing to do by default
    }

    /**
     * A team is about to be removed. Fired before the document is deleted;
     * throwing stops the deletion. Must be idempotent.
     */
    default void onTeamDeleted(String tenantId, String name) {
        // nothing to do by default
    }
}
