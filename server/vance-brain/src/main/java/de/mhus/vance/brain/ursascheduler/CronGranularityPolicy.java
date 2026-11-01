package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.ursascheduler.SchedulerGranularity;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Normalises a 6-field cron expression to the cadence an installation
 * allows ({@link SchedulerGranularity}). Pure and deterministic: the same
 * input yields the same output on every pod and across restarts, which is
 * what keeps a spread minute stable.
 *
 * <p>The YAML a user wrote is never rewritten — only the trigger the
 * brain registers sees the normalised value.
 */
final class CronGranularityPolicy {

    private static final int MINUTES_PER_HOUR = 60;

    private CronGranularityPolicy() {}

    /** Parses the {@code vance.scheduler.granularity} property, case-insensitive. */
    static SchedulerGranularity parse(String raw) {
        try {
            return SchedulerGranularity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "vance.scheduler.granularity must be one of seconds, minutes, hours — got '" + raw + "'", ex);
        }
    }

    /**
     * @param cron      the cron as written; anything but 6 fields is returned unchanged
     * @param spreadKey identifies the scheduler (tenant/project/name); only used for
     *                  {@link SchedulerGranularity#HOURS}
     */
    static String apply(String cron, SchedulerGranularity granularity, String spreadKey) {
        if (granularity == SchedulerGranularity.SECONDS) return cron;
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 6) return cron;
        fields[0] = "0";
        if (granularity == SchedulerGranularity.HOURS) {
            fields[1] = Integer.toString(spreadMinute(spreadKey));
        }
        return String.join(" ", fields);
    }

    /** Stable minute of the hour in {@code 0..59} for a scheduler identity. */
    static int spreadMinute(String spreadKey) {
        CRC32 crc = new CRC32();
        crc.update(spreadKey.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % MINUTES_PER_HOUR);
    }
}
