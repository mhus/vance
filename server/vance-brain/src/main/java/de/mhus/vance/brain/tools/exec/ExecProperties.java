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
     * Directory that holds per-project / per-job state (logs, metadata).
     * Resolved against the JVM's working directory if relative.
     */
    private String baseDir = "data/exec";

    /** Milliseconds {@code work_exec_run} blocks before handing back the id. */
    private long defaultWaitMs = 15_000;

    /** Inline stdout/stderr cap in characters. Files on disk stay complete. */
    private int inlineOutputCharCap = 8_000;

    /** Per-project cap on retained jobs. Oldest completed jobs drop out first. */
    private int maxJobsPerProject = 32;

    /**
     * Number of stdout/stderr tail lines carried on the
     * {@code EXEC_FINISHED} push event. Just enough for the LLM to
     * spot the error message or success marker without round-tripping
     * the full log — full output stays on disk and is fetchable via
     * {@code work_exec_tail} on demand.
     */
    private int completionTailLines = 40;
}
