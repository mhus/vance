package de.mhus.vance.api.projects;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What is known of a runner. {@link #UNKNOWN} means no live entry exists
 * (never reported, entry expired, Redis off).
 */
@GenerateTypeScript("projects")
public enum RunnerPhase {

    /** No live entry. */
    UNKNOWN,

    /** Recorded, not yet applied or not yet ready. */
    PENDING,

    /** The runner is up. */
    READY,

    /** The runner failed. */
    FAILED,

    /** The runner is switched off on purpose. */
    DISABLED
}
