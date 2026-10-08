package de.mhus.vance.shared.project.maintenance;

/**
 * Removes a per-user hub project <em>with</em> everything in it — the full
 * {@link ProjectDataHandler} sweep, then the project document.
 *
 * <p>Exists for the one account kind whose end is not an operator decision
 * at a terminal: the Trillian service account. Its hub
 * ({@code _user__trillian-…}) holds the loop session, its chat, schedules,
 * goals and settings, and the account goes when its control session ends.
 * Dropping only the hub's project document there strands every row keyed by
 * the hub name — and the next Trillian minted under the same name inherits
 * them, schedules that fire included.
 *
 * <p>Two implementations, one per process that collects handlers: the admin
 * shell runs it through its {@code ProjectMaintenanceService}; the brain has a
 * narrow sweeper that accepts nothing but Trillian hubs. Consumers inject
 * {@code ObjectProvider<UserHubSweeper>} — a process without one simply cannot
 * sweep, and says so in its log.
 *
 * <p><b>Same invariant as the maintenance delete.</b> The project document is
 * the index back to the data, so it is removed only when every handler
 * succeeded; a partial sweep returns {@code false} and a re-run finishes it.
 */
public interface UserHubSweeper {

    /**
     * Sweeps {@code hubProjectName} and removes it.
     *
     * @return {@code true} when the hub is gone afterwards (or never existed),
     *     {@code false} when a handler failed and the project document was kept
     * @throws IllegalArgumentException when the name is not a hub this
     *     implementation may remove
     */
    boolean sweepUserHub(String tenantId, String hubProjectName);
}
