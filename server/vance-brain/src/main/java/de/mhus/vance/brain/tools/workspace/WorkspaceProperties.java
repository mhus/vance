package de.mhus.vance.brain.tools.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.workspace.*} — tunables for the session-scoped file
 * workspace. Defaults are dev-friendly; production should point
 * {@link #baseDir} at a writable, monitored volume.
 */
@Data
@ConfigurationProperties(prefix = "vance.workspace")
public class WorkspaceProperties {

    /**
     * Base directory under which each session gets a sub-directory
     * named by its {@code sessionId}. Resolved against the JVM's
     * working directory if relative.
     */
    private String baseDir = "data/workspaces";

    /** Hard cap on the characters returned by a single read. */
    private int defaultReadCharCap = 8_000;
}
