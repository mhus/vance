package de.mhus.vance.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Single shell-exec job. Mutable because pump threads append output
 * and the worker flips status/exitCode as the process progresses.
 * {@code volatile} on the flippable fields is enough — no method
 * reads two correlated fields at once.
 */
public final class ExecJob {

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        KILLED,
        ORPHANED
    }

    private final String id;
    private final String projectId;
    private final @Nullable String ownerProcessId;
    private final String command;
    private final Path stdoutFile;
    private final Path stderrFile;
    private final Instant startedAt;
    /**
     * Subprocess environment to install, built by {@link
     * ExecEnvironmentBuilder} at submit time (sealed base + pass-throughs +
     * extras). {@code null} only for the non-spawning convenience
     * constructors used by tests and renderers — the runner installs this
     * map verbatim and <b>never</b> falls back to inheriting the JVM env.
     */
    private final @Nullable Map<String, String> env;
    /**
     * Per-instance metadata for cross-cutting filters (Cortex doc
     * linkage, runtime kind, source). Stays in-memory; never goes to
     * Micrometer or Mongo.
     */
    private final Map<String, String> labels;

    /**
     * Hard cap on each in-memory output buffer (code-review Phase 2). A
     * long-running / chatty job would otherwise grow {@code stdout}/
     * {@code stderr} without bound on the heap. Generous — well above the
     * inline render cap — and only the oldest chars are dropped; the full
     * output stays in the on-disk log for {@code tail}.
     */
    private static final int MAX_BUFFER_CHARS = 64 * 1024;

    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    private volatile Status status = Status.RUNNING;
    private volatile @Nullable Integer exitCode;
    private volatile @Nullable Instant finishedAt;
    private volatile @Nullable Process process;
    private volatile Instant lastOutputAt;
    private volatile @Nullable Instant deadline;
    private volatile boolean killedByWatchdog;

    public ExecJob(String id, String projectId, String command, Path stdoutFile, Path stderrFile) {
        this(id, projectId, null, command, stdoutFile, stderrFile, null, Map.of());
    }

    public ExecJob(
            String id,
            String projectId,
            @Nullable String ownerProcessId,
            String command,
            Path stdoutFile,
            Path stderrFile) {
        this(id, projectId, ownerProcessId, command, stdoutFile, stderrFile, null, Map.of());
    }

    public ExecJob(
            String id,
            String projectId,
            @Nullable String ownerProcessId,
            String command,
            Path stdoutFile,
            Path stderrFile,
            @Nullable Map<String, String> env,
            Map<String, String> labels) {
        this.id = id;
        this.projectId = projectId;
        this.ownerProcessId = ownerProcessId;
        this.command = command;
        this.stdoutFile = stdoutFile;
        this.stderrFile = stderrFile;
        this.env = env == null ? null : Map.copyOf(env);
        this.labels = Map.copyOf(labels);
        this.startedAt = Instant.now();
        this.lastOutputAt = this.startedAt;
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    @Nullable
    public String ownerProcessId() {
        return ownerProcessId;
    }

    public String command() {
        return command;
    }

    public Path stdoutFile() {
        return stdoutFile;
    }

    public Path stderrFile() {
        return stderrFile;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @Nullable
    public Map<String, String> env() {
        return env;
    }

    public Map<String, String> labels() {
        return labels;
    }

    @Nullable
    public Instant finishedAt() {
        return finishedAt;
    }

    public void finishedAt(Instant t) {
        this.finishedAt = t;
    }

    public Status status() {
        return status;
    }

    public void status(Status s) {
        this.status = s;
    }

    @Nullable
    public Integer exitCode() {
        return exitCode;
    }

    public void exitCode(@Nullable Integer c) {
        this.exitCode = c;
    }

    @Nullable
    public Process process() {
        return process;
    }

    public void process(Process p) {
        this.process = p;
    }

    public void appendStdout(String line) {
        synchronized (stdout) {
            stdout.append(line).append('\n');
            capBuffer(stdout);
        }
        lastOutputAt = Instant.now();
    }

    public void appendStderr(String line) {
        synchronized (stderr) {
            stderr.append(line).append('\n');
            capBuffer(stderr);
        }
        lastOutputAt = Instant.now();
    }

    /**
     * Keeps only the most recent {@link #MAX_BUFFER_CHARS} chars in the
     * in-memory buffer — drop the oldest overflow. Caller holds the
     * buffer's monitor.
     */
    private static void capBuffer(StringBuilder sb) {
        int over = sb.length() - MAX_BUFFER_CHARS;
        if (over > 0) {
            sb.delete(0, over);
        }
    }

    public Instant lastOutputAt() {
        return lastOutputAt;
    }

    public String readStdout() {
        synchronized (stdout) {
            return stdout.toString();
        }
    }

    public String readStderr() {
        synchronized (stderr) {
            return stderr.toString();
        }
    }

    public boolean isTerminal() {
        return status != Status.RUNNING;
    }

    @Nullable
    public Instant deadline() {
        return deadline;
    }

    /**
     * Sets the deadline at submit-time, before the worker is started.
     * Subsequent changes must go through {@link #extendDeadline(Instant)}
     * which guards against racing with a watchdog that has already
     * picked the job up.
     */
    public void initialDeadline(@Nullable Instant deadline) {
        this.deadline = deadline;
    }

    /**
     * Pushes the deadline out. Synchronised against
     * {@link #attemptWatchdogKill()} so a watchdog firing right now
     * either wins (kill) or loses (extension) — never both. Returns
     * {@code false} when the job is no longer RUNNING.
     */
    public synchronized boolean extendDeadline(Instant newDeadline) {
        if (status != Status.RUNNING) {
            return false;
        }
        this.deadline = newDeadline;
        return true;
    }

    /**
     * Watchdog entry point. Atomically transitions a still-RUNNING
     * job to KILLED with the watchdog-marker so the completion-push
     * path knows to emit {@link
     * de.mhus.vance.api.thinkprocess.ProcessEventType#EXEC_TIMEOUT}
     * rather than {@code EXEC_FINISHED}. Returns {@code true} when
     * the kill was claimed; {@code false} when the job already
     * terminated (natural completion / user-driven kill won the race).
     */
    public synchronized boolean attemptWatchdogKill() {
        if (status != Status.RUNNING) {
            return false;
        }
        status = Status.KILLED;
        killedByWatchdog = true;
        finishedAt = Instant.now();
        return true;
    }

    public boolean killedByWatchdog() {
        return killedByWatchdog;
    }

    /**
     * Reconciliation entry point for the orphan sweeper. Atomically
     * transitions a still-RUNNING job to {@link Status#ORPHANED} — used
     * when the worker thread died without running its {@code finally}
     * (a hard Error / thread-death that skipped the terminal transition),
     * leaving the job stuck RUNNING forever. Synchronised against
     * {@link #attemptWatchdogKill()} / {@link #extendDeadline(Instant)}
     * and consistent with the worker's status writes: returns {@code
     * false} when the job already terminated, so a real {@code finally}
     * that just set COMPLETED/FAILED/KILLED is never clobbered.
     */
    public synchronized boolean markOrphanedIfRunning() {
        if (status != Status.RUNNING) {
            return false;
        }
        status = Status.ORPHANED;
        finishedAt = Instant.now();
        return true;
    }
}
