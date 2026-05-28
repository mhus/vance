package de.mhus.vance.api.ursaevents;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Cascade tier that provides a resolved event. Unlike recipes there
 * is intentionally no resource tier — events are always project- or
 * tenant-specific (analog to schedulers/workflows).
 */
@GenerateTypeScript("events")
public enum EventSource {
    PROJECT,
    TENANT
}
