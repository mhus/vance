package de.mhus.vance.brain.tools.exec;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.exec.*} — tunables for the shell exec tool.
 */
@Data
@ConfigurationProperties(prefix = "vance.exec")
public class ExecProperties {

    /**
     * Directory that holds per-session / per-job state (logs, metadata).
     * Resolved against the JVM's working directory if relative.
     */
    private String baseDir = "data/exec";

    /** Milliseconds {@code exec_run} blocks before handing back the id. */
    private long defaultWaitMs = 15_000;

    /** Inline stdout/stderr cap in characters. Files on disk stay complete. */
    private int inlineOutputCharCap = 8_000;

    /** Per-session cap on retained jobs. Oldest completed jobs drop out first. */
    private int maxJobsPerSession = 32;
}
