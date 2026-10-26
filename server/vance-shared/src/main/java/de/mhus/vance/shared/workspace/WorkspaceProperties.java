package de.mhus.vance.shared.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.workspace.*} — tunables for the per-project on-disk workspace.
 * Defaults are dev-friendly; production should point {@link #root} at a
 * monitored, writable volume.
 */
@Data
@ConfigurationProperties(prefix = "vance.workspace")
public class WorkspaceProperties {

    /**
     * Filesystem root under which each project gets a sub-directory named
     * by its {@code projectId}. Resolved against the JVM's working
     * directory if relative.
     */
    private String root = System.getProperty("user.home") + "/.vancetope/workspaces";

    /** Hard cap on characters returned by a single read. */
    private int defaultReadCharCap = 8_000;

    /**
     * Soft disk-pressure threshold (percent of {@link #root}'s filesystem
     * used). Above this, {@code createRootDir} and the temp convenience
     * methods log a warning. Caller can still proceed.
     */
    private int softLimitPercent = 80;

    /**
     * Hard disk-pressure threshold. Above this, {@code createRootDir}
     * and the temp convenience methods reject with
     * {@link WorkspaceQuotaExceededException}. Tools surface that as a
     * tool error so the LLM can react.
     */
    private int hardLimitPercent = 95;

    /** {@code vance.workspace.node.*} — npm used for Node RootDirs (script libraries). */
    private Node node = new Node();

    @Data
    public static class Node {

        /** npm binary. Operator config only — never taken from RootDir metadata. */
        private String npmPath = "npm";

        /** Registry passed on every npm command line; a {@code .npmrc} in the RootDir cannot redirect it. */
        private String registry = "https://registry.npmjs.org/";

        /** Upper bound for one RootDir's {@code node_modules}; an install that exceeds it is rolled back. */
        private long maxBytes = 512L * 1024 * 1024;
    }
}
