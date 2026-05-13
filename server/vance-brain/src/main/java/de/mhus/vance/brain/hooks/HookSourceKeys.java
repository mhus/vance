package de.mhus.vance.brain.hooks;

/**
 * Source-key conventions for the generic {@code event_log} rows
 * produced by hook runs. Hook runs use {@code "hook:<event>:<name>"}
 * — e.g. {@code "hook:process.completed:notify-slack"} — so the
 * scheduler-shared event-log table can be queried per hook or per
 * event prefix.
 */
public final class HookSourceKeys {

    /** Prefix shared by every hook-produced event-log row. */
    public static final String SOURCE_PREFIX = "hook:";

    private HookSourceKeys() {}

    public static String sourceFor(String eventWireName, String hookName) {
        return SOURCE_PREFIX + eventWireName + ":" + hookName;
    }
}
