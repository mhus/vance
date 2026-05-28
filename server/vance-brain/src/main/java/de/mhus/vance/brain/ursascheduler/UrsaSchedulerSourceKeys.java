package de.mhus.vance.brain.ursascheduler;

/**
 * Source-string conventions for scheduler entries in the event log.
 * Kept in one place so producers and consumers (lifecycle listener,
 * REST controller, agent tools) agree on the same format.
 *
 * <p>See {@code specification/scheduler.md} §7.
 */
public final class UrsaSchedulerSourceKeys {

    /** Prefix used to scope event-log queries to scheduler-spawned activity. */
    public static final String SOURCE_PREFIX = "ursascheduler:";

    /** Display-name pattern for the dedicated system session of a scheduler. */
    public static final String SYSTEM_SESSION_PREFIX = "_ursascheduler_";

    private UrsaSchedulerSourceKeys() {
    }

    public static String sourceFor(String schedulerName) {
        return SOURCE_PREFIX + schedulerName;
    }

    public static String systemSessionDisplayName(String schedulerName) {
        return SYSTEM_SESSION_PREFIX + schedulerName;
    }
}
