package de.mhus.vance.api.scheduler;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Cascade tier a scheduler doc was resolved from — see
 * {@code specification/scheduler.md} §3. No {@code RESOURCE} layer:
 * schedulers are by definition project- or tenant-specific.
 */
@GenerateTypeScript("scheduler")
public enum SchedulerSource {
    /** Lives under the project — overrides tenant-level entries with the same name. */
    PROJECT,
    /** Lives under {@code _vance/scheduler/} — applies to every project in the tenant. */
    TENANT
}
