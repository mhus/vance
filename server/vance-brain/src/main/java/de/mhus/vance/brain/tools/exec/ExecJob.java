package de.mhus.vance.brain.tools.exec;

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
final class ExecJob {

    enum Status { RUNNING, COMPLETED, FAILED, KILLED, ORPHANED }

    private final String id;
    private final String projectId;
    private final @Nullable String ownerProcessId;
    private final String command;
    private final Path stdoutFile;
    private final Path stderrFile;
    private final Instant startedAt;
    /**
     * Subprocess environment to install. {@code null} = inherit JVM env
     * (legacy default for {@code exec_run} callers). When non-null the
     * runner wipes inherited vars and installs only these — used by
     * script-execution paths that need a sealed env (e.g. Python with
     * {@code VANCE_TOKEN}).
     */
    private final @Nullable Map<String, String> env;
    /**
     * Per-instance metadata for cross-cutting filters (Cortex doc
     * linkage, runtime kind, source). Stays in-memory; never goes to
     * Micrometer or Mongo.
     */
    private final Map<String, String> labels;

    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    private volatile Status status = Status.RUNNING;
    private volatile @Nullable Integer exitCode;
    private volatile @Nullable Instant finishedAt;
    private volatile @Nullable Process process;
    private volatile Instant lastOutputAt;
    private volatile @Nullable Instant deadline;
    private volatile boolean killedByWatchdog;

    ExecJob(String id, String projectId, String command, Path stdoutFile, Path stderrFile) {
        this(id, projectId, null, command, stdoutFile, stderrFile, null, Map.of());
    }

    ExecJob(
            String id,
            String projectId,
            @Nullable String ownerProcessId,
            String command,
            Path stdoutFile,
            Path stderrFile) {
        this(id, projectId, ownerProcessId, command, stdoutFile, stderrFile, null, Map.of());
    }

    ExecJob(
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

    String id() { return id; }
    String projectId() { return projectId; }
    @Nullable String ownerProcessId() { return ownerProcessId; }
    String command() { return command; }
    Path stdoutFile() { return stdoutFile; }
    Path stderrFile() { return stderrFile; }
    Instant startedAt() { return startedAt; }
    @Nullable Map<String, String> env() { return env; }
    Map<String, String> labels() { return labels; }

    @Nullable Instant finishedAt() { return finishedAt; }
    void finishedAt(Instant t) { this.finishedAt = t; }

    Status status() { return status; }
    void status(Status s) { this.status = s; }

    @Nullable Integer exitCode() { return exitCode; }
    void exitCode(@Nullable Integer c) { this.exitCode = c; }

    @Nullable Process process() { return process; }
    void process(Process p) { this.process = p; }

    void appendStdout(String line) {
        synchronized (stdout) { stdout.append(line).append('\n'); }
        lastOutputAt = Instant.now();
    }

    void appendStderr(String line) {
        synchronized (stderr) { stderr.append(line).append('\n'); }
        lastOutputAt = Instant.now();
    }

    Instant lastOutputAt() {
        return lastOutputAt;
    }

    String readStdout() {
        synchronized (stdout) { return stdout.toString(); }
    }

    String readStderr() {
        synchronized (stderr) { return stderr.toString(); }
    }

    boolean isTerminal() {
        return status != Status.RUNNING;
    }

    @Nullable Instant deadline() { return deadline; }

    /**
     * Sets the deadline at submit-time, before the worker is started.
     * Subsequent changes must go through {@link #extendDeadline(Instant)}
     * which guards against racing with a watchdog that has already
     * picked the job up.
     */
    void initialDeadline(@Nullable Instant deadline) {
        this.deadline = deadline;
    }

    /**
     * Pushes the deadline out. Synchronised against
     * {@link #attemptWatchdogKill()} so a watchdog firing right now
     * either wins (kill) or loses (extension) — never both. Returns
     * {@code false} when the job is no longer RUNNING.
     */
    synchronized boolean extendDeadline(Instant newDeadline) {
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
    synchronized boolean attemptWatchdogKill() {
        if (status != Status.RUNNING) {
            return false;
        }
        status = Status.KILLED;
        killedByWatchdog = true;
        finishedAt = Instant.now();
        return true;
    }

    boolean killedByWatchdog() {
        return killedByWatchdog;
    }
}
