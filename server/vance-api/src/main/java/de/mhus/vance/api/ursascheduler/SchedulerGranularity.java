package de.mhus.vance.api.ursascheduler;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The finest firing cadence a brain lets cron schedulers keep. It is an
 * operator decision ({@code vance.scheduler.granularity}), not a
 * per-scheduler field: the YAML a user writes stays accepted as written,
 * the registration normalises it to what the installation allows. See
 * {@code specification/public/scheduler.md} §10d.
 */
@GenerateTypeScript("scheduler")
public enum SchedulerGranularity {
    /** Second precision — the seconds field of the cron is honoured as written. */
    SECONDS,
    /** At most once per minute — the seconds field is clamped to {@code 0}. This is the default. */
    MINUTES,
    /**
     * At most once per hour — the seconds field is clamped to {@code 0} and the
     * minute field is replaced by a stable per-scheduler offset, so the fires of
     * many schedulers spread over the hour instead of all landing on minute 0.
     * A scheduler therefore does <em>not</em> fire at an exact, predictable minute.
     */
    HOURS
}
