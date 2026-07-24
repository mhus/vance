package de.mhus.vance.brain.tools.exec;

import java.time.Duration;
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

    /**
     * Grace between SIGTERM and SIGKILL when terminating a job's process tree.
     * The process (and its descendants) get SIGTERM first for a clean shutdown
     * (checkpoint, flush); survivors are SIGKILL'd after this. {@code 0} = kill
     * immediately (no grace).
     */
    private long killGraceMs = 10_000;

    /** Inline stdout/stderr cap in characters. Files on disk stay complete. */
    private int inlineOutputCharCap = 8_000;

    /** Per-project cap on retained jobs. Oldest completed jobs drop out first. */
    private int maxJobsPerProject = 32;

    /**
     * How long a job may stay {@code RUNNING} with a dead/absent OS process
     * before the orphan sweeper reconciles it to {@code ORPHANED}. Guards
     * the rare case where the worker thread died without running its {@code
     * finally} (a hard Error/thread-death), leaving the job stuck RUNNING
     * forever — which pins its session alive (via {@code
     * hasActiveJobsForSession}) and blocks the IdleSweeper. Measured from the
     * job's last output; generous so a legitimately quiet-but-live job (its
     * process is still alive) is never touched, only genuinely dead ones.
     */
    private Duration orphanReconcileTtl = Duration.ofMinutes(2);

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
