package de.mhus.vance.brain.tools.exec;

import lombok.Data;
import org.jspecify.annotations.Nullable;
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

    /**
     * Opt-in OS-isolation for {@code work_exec_run}. The exec gate already
     * confines <em>file tools</em> to the RootDir, but a shell command can
     * still read arbitrary paths; isolation wraps the command in a
     * sandboxing tool so it physically only sees its RootDir. Off by default.
     */
    private Isolation isolation = new Isolation();

    /**
     * {@code mode: custom} wraps the command in {@link #wrapper} (a
     * whitespace-separated argv template with {@code {workdir}} = the job's
     * RootDir cwd and {@code {cmd}} = the command, kept as one argv element).
     * {@code mode: none} (default) runs the command directly under the shell.
     */
    @Data
    public static class Isolation {
        private String mode = "none";
        private @Nullable String wrapper;
    }
}
