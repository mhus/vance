package de.mhus.vance.brain.scheduler;

/**
 * Source-string conventions for scheduler entries in the event log.
 * Kept in one place so producers and consumers (lifecycle listener,
 * REST controller, agent tools) agree on the same format.
 *
 * <p>See {@code specification/scheduler.md} §7.
 */
public final class SchedulerSourceKeys {

    /** Prefix used to scope event-log queries to scheduler-spawned activity. */
    public static final String SOURCE_PREFIX = "scheduler:";

    /** Display-name pattern for the dedicated system session of a scheduler. */
    public static final String SYSTEM_SESSION_PREFIX = "_scheduler_";

    private SchedulerSourceKeys() {
    }

    public static String sourceFor(String schedulerName) {
        return SOURCE_PREFIX + schedulerName;
    }

    public static String systemSessionDisplayName(String schedulerName) {
        return SYSTEM_SESSION_PREFIX + schedulerName;
    }
}
