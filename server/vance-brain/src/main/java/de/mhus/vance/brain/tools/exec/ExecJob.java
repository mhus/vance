package de.mhus.vance.brain.tools.exec;

import java.nio.file.Path;
import java.time.Instant;
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
    private final String sessionId;
    private final String command;
    private final Path stdoutFile;
    private final Path stderrFile;
    private final Instant startedAt;

    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    private volatile Status status = Status.RUNNING;
    private volatile @Nullable Integer exitCode;
    private volatile @Nullable Instant finishedAt;
    private volatile @Nullable Process process;

    ExecJob(String id, String sessionId, String command, Path stdoutFile, Path stderrFile) {
        this.id = id;
        this.sessionId = sessionId;
        this.command = command;
        this.stdoutFile = stdoutFile;
        this.stderrFile = stderrFile;
        this.startedAt = Instant.now();
    }

    String id() { return id; }
    String sessionId() { return sessionId; }
    String command() { return command; }
    Path stdoutFile() { return stdoutFile; }
    Path stderrFile() { return stderrFile; }
    Instant startedAt() { return startedAt; }

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
    }

    void appendStderr(String line) {
        synchronized (stderr) { stderr.append(line).append('\n'); }
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
}
