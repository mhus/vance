package de.mhus.vance.api.projects;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What a project is doing right now, as the user sees it. Read from the
 * ephemeral live state in Redis, never from the database: the project's
 * stored actual state is only valid while its lease lives, and the live state
 * has to say so honestly when nobody can vouch for it.
 */
@GenerateTypeScript("projects")
public enum ProjectLiveState {

    /** Nothing vouches for the project: no live entry, the entry expired, or Redis is off. */
    UNKNOWN,

    /** Not running anywhere. */
    STOPPED,

    /** The holding pod is bringing the project up. */
    STARTING,

    /** The holding pod serves the project. */
    RUNNING,

    /** The holding pod is taking the project down. */
    STOPPING
}
