package de.mhus.vance.api.scheduler;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Behaviour when a scheduler tick fires while the previous run is still
 * active. See {@code specification/scheduler.md} §5.
 */
@GenerateTypeScript("scheduler")
public enum OverlapPolicy {
    /** New tick is dropped. Default. */
    SKIP,
    /** New tick is queued; runs after the current process terminates. Max one waiting. */
    QUEUE,
    /** Running process is stopped, then the new run starts. */
    CANCEL_PREVIOUS
}
