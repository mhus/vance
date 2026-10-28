package de.mhus.vance.exec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Runs commands as background jobs in virtual threads with live output
 * persistence — the plain-Java core of the brain's {@code ExecManager} and of the
 * runner's exec jobs ({@code planning/runner.md}).
 *
 * <p>Jobs are scoped by a caller-chosen <em>scope key</em> (the brain uses
 * {@code tenant/project}): the outer index is the scope, the inner the job id, so
 * one scope cannot see another's jobs by stumbling on an id.
 *
 * <p>Output flows two ways on purpose: line-by-line into a log file (complete
 * record, readable after truncation), and into an in-memory buffer on the
 * {@link ExecJob} (fast inline response).
 *
 * <p>What happens around a job — mirroring its state into a registry, delivering a
 * completion event, releasing things tied to an evicted job — is the caller's
 * business and reaches it through {@link Listener}. The engine itself knows
 * nothing of tenants, workspaces or think-processes.
 */
@Slf4j
public final class ExecJobs implements AutoCloseable {

    /**
     * @param baseDir        per-scope, per-job log directories live below it
     * @param killGraceMs    grace between SIGTERM and SIGKILL when terminating a
     *                       job's process tree; {@code 0} = kill immediately
     * @param maxJobsPerScope oldest terminal jobs drop out beyond this
     * @param orphanTtl      silence after which a RUNNING job with a dead process
     *                       counts as orphaned
     */
    public record Config(Path baseDir, long killGraceMs, int maxJobsPerScope, Duration orphanTtl) {}

    /** What to start. {@code argv} is final — the caller already applied isolation. */
    public record Submission(
            String scopeKey,
            String projectId,
            @Nullable String ownerProcessId,
            String command,
            List<String> argv,
            Path cwd,
            @Nullable Map<String, String> env,
            Map<String, String> labels,
            @Nullable Instant deadline) {}

    /** Callbacks around a job's life; all may be called from worker threads. */
    public interface Listener {

        /** The job's state moved to a terminal one (finished, killed, orphaned): mirror it. */
        void stateChanged(ExecJob job);

        /**
         * The job ended on its own or was reconciled as orphaned: deliver the
         * completion. Not called for {@link #kill}, which only changes state.
         */
        void finished(ExecJob job);

        /** The job dropped out of the index to stay under the cap. */
        void evicted(ExecJob job);
    }

    /** Selector for {@link #tail}. */
    public enum Stream {
        STDOUT,
        STDERR
    }

    private final Config config;
    private final Listener listener;

    private final Map<String, Map<String, ExecJob>> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Drives deadline-based kills. One scheduled task per job, keyed by job id so
     * {@link #extendDeadline} can cancel and reschedule cleanly. A single daemon
     * thread is enough — the fire callback dispatches the actual kill, a few
     * sub-millisecond map lookups plus {@code Process.destroyForcibly()}.
     */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(watchdogThreadFactory());

    private final Map<String, ScheduledFuture<?>> watchdogFutures = new ConcurrentHashMap<>();

    public ExecJobs(Config config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    // ──────────────────── Public API ────────────────────

    /** Starts the job immediately and returns it; observe it via {@link #waitFor} or {@link #get}. */
    public ExecJob submit(Submission s) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Path jobDir = jobDir(s.scopeKey(), jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ExecException("Cannot create exec job dir: " + e.getMessage(), e);
        }
        ExecJob job = new ExecJob(
                jobId,
                s.projectId(),
                s.ownerProcessId(),
                s.command(),
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"),
                s.env(),
                s.labels());
        Instant deadline = s.deadline();
        if (deadline != null) {
            job.initialDeadline(deadline);
        }
        indexJob(s.scopeKey(), job);
        workers.submit(() -> runJob(job, s.argv(), s.cwd()));
        if (deadline != null) {
            rearmWatchdog(job, deadline);
        }
        return job;
    }

    /**
     * Pushes the deadline of {@code jobId} out by {@code extension} from now.
     * Returns {@code false} when the job is unknown or no longer RUNNING (the
     * natural completion or the watchdog kill already happened — the caller's
     * next read sees the terminal status).
     */
    public boolean extendDeadline(String scopeKey, String jobId, Duration extension) {
        if (extension == null || extension.isNegative() || extension.isZero()) {
            throw new ExecException("extension must be positive");
        }
        ExecJob job = get(scopeKey, jobId).orElse(null);
        if (job == null) {
            return false;
        }
        Instant newDeadline = Instant.now().plus(extension);
        if (!job.extendDeadline(newDeadline)) {
            return false;
        }
        rearmWatchdog(job, newDeadline);
        return true;
    }

    /** Blocks up to {@code maxMillis} for a RUNNING job to finish. */
    public ExecJob waitFor(ExecJob job, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return job;
    }

    /** Look up a job by scope and id. */
    public Optional<ExecJob> get(String scopeKey, String jobId) {
        Map<String, ExecJob> perScope = jobs.get(scopeKey);
        if (perScope == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(perScope.get(jobId));
    }

    /** All jobs of a scope, insertion-ordered (oldest first). */
    public Collection<ExecJob> list(String scopeKey) {
        Map<String, ExecJob> perScope = jobs.get(scopeKey);
        return perScope == null ? List.of() : perScope.values();
    }

    /** Compact status snapshot — no stdout/stderr bodies. */
    public Optional<ExecStat> stat(String scopeKey, String jobId) {
        return get(scopeKey, jobId).map(ExecJobs::toStat);
    }

    public static ExecStat toStat(ExecJob job) {
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        return new ExecStat(
                job.id(),
                job.projectId(),
                job.command(),
                job.status(),
                job.startedAt(),
                job.lastOutputAt(),
                job.finishedAt(),
                job.exitCode(),
                Duration.between(job.startedAt(), end).toMillis(),
                fileSize(job.stdoutFile()),
                fileSize(job.stderrFile()),
                fileMtime(job.stdoutFile()),
                fileMtime(job.stderrFile()),
                job.stdoutFile().toString(),
                job.stderrFile().toString());
    }

    /**
     * Last {@code n} lines of the requested stream, oldest first. Reads from the
     * persisted log file, so callers see what {@code tail -n} would on the host.
     */
    public List<String> tail(String scopeKey, String jobId, int n, Stream stream) {
        if (n <= 0) {
            return List.of();
        }
        ExecJob job = get(scopeKey, jobId)
                .orElseThrow(() -> new ExecException("Unknown exec job: '" + jobId + "' (not in this project)"));
        Path file = stream == Stream.STDERR ? job.stderrFile() : job.stdoutFile();
        return tailFile(file, n);
    }

    public static List<String> tailFile(Path file, int n) {
        if (!Files.isReadable(file)) {
            return List.of();
        }
        Deque<String> ring = new ArrayDeque<>(n);
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (ring.size() == n) {
                    ring.removeFirst();
                }
                ring.addLast(line);
            }
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(ring);
    }

    /** Force-kill a still-running job. Returns {@code false} if already terminal. */
    public boolean kill(String scopeKey, String jobId) {
        ExecJob job = get(scopeKey, jobId).orElse(null);
        if (job == null || job.isTerminal()) {
            return false;
        }
        Process p = job.process();
        if (p == null) {
            return false;
        }
        job.status(ExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        terminateTree(p);
        cancelWatchdog(jobId);
        listener.stateChanged(job);
        return true;
    }

    /**
     * Drops a scope from the table and deletes its job logs: what a project delete or rename
     * leaves of the jobs. Running jobs must have been killed first ({@link #killAll}).
     *
     * @return how many jobs were forgotten
     */
    public int forgetScope(String scopeKey) {
        Map<String, ExecJob> perScope = jobs.remove(scopeKey);
        if (perScope == null) {
            return 0;
        }
        for (String jobId : List.copyOf(perScope.keySet())) {
            deleteJobDirQuietly(scopeKey, jobId);
            cancelWatchdog(jobId);
        }
        return perScope.size();
    }

    /** Force-kills every running job of a scope. Returns how many were killed. */
    public int killAll(String scopeKey) {
        Map<String, ExecJob> perScope = jobs.get(scopeKey);
        if (perScope == null) {
            return 0;
        }
        int killed = 0;
        synchronized (perScope) {
            for (ExecJob j : perScope.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.status(ExecJob.Status.KILLED);
                    j.finishedAt(Instant.now());
                    terminateTree(j.process());
                    cancelWatchdog(j.id());
                    listener.stateChanged(j);
                    killed++;
                }
            }
        }
        return killed;
    }

    /**
     * Reconciles jobs stuck in {@code RUNNING} because their worker thread died
     * without running its {@code finally} (a hard Error / thread-death that skipped
     * the terminal transition). Such a job would otherwise stay RUNNING for the
     * process's lifetime and leave its owner waiting for a completion that never
     * comes. A stuck job is completed as {@link ExecJob.Status#ORPHANED}.
     *
     * <p>Process-local by design: the index and the {@code Process} handles only
     * exist in this JVM.
     *
     * <p>False-positive-safe: a job is only reconciled when its OS process is
     * dead/absent <em>and</em> it has shown no output for longer than the
     * configured TTL. A legitimately long-running but quiet job has a live process
     * and is never touched.
     *
     * @return the number of jobs reconciled to ORPHANED this pass
     */
    public int reconcileOrphans(Instant now) {
        Duration ttl = config.orphanTtl();
        // Collect candidates under each per-scope monitor (required for safe
        // iteration of the synchronizedMap), then act outside the lock so the
        // listener's I/O never blocks submit/indexJob.
        List<ExecJob> candidates = new ArrayList<>();
        for (Map<String, ExecJob> perScope : jobs.values()) {
            synchronized (perScope) {
                for (ExecJob job : perScope.values()) {
                    if (isStuckOrphan(job, now, ttl)) {
                        candidates.add(job);
                    }
                }
            }
        }
        int reconciled = 0;
        for (ExecJob job : candidates) {
            // Re-check atomically: a real finally may have terminated the job
            // between collection and now — then this is a no-op.
            if (!job.markOrphanedIfRunning()) {
                continue;
            }
            log.warn(
                    "Reconciled orphaned exec job '{}' (RUNNING with dead process, no output for >{}) → ORPHANED",
                    job.id(),
                    ttl);
            listener.stateChanged(job);
            cancelWatchdog(job.id());
            listener.finished(job);
            reconciled++;
        }
        return reconciled;
    }

    /**
     * A job is a stuck orphan when it is still RUNNING, its OS process is dead or
     * was never assigned (the worker never reached {@code pb.start()} or died right
     * after the process exited), and it has been silent past the TTL. The silence
     * dwell also covers the tiny window between {@code Process.waitFor()} returning
     * and the worker setting the terminal status, so a normally-completing job is
     * never misclassified.
     */
    public static boolean isStuckOrphan(ExecJob job, Instant now, Duration ttl) {
        if (job.isTerminal()) {
            return false;
        }
        Process p = job.process();
        boolean processDeadOrAbsent = (p == null) || !p.isAlive();
        if (!processDeadOrAbsent) {
            return false;
        }
        return Duration.between(job.lastOutputAt(), now).compareTo(ttl) > 0;
    }

    /** Force-kills what still runs and stops the executors. */
    @Override
    public void close() {
        for (Map<String, ExecJob> perScope : jobs.values()) {
            for (ExecJob j : perScope.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                }
            }
        }
        workers.shutdownNow();
        watchdog.shutdownNow();
        watchdogFutures.clear();
    }

    // ──────────────────── Runner ────────────────────

    private void runJob(ExecJob job, List<String> argv, Path cwd) {
        try (BufferedWriter stdoutW = openLog(job.stdoutFile());
                BufferedWriter stderrW = openLog(job.stderrFile())) {

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            // Every job runs sealed: the map was built at submit time by the
            // caller. The wipe is unconditional so a null map can never fall
            // back to inheriting the host process's environment.
            Map<String, String> processEnv = pb.environment();
            processEnv.clear();
            Map<String, String> jobEnv = job.env();
            if (jobEnv != null) {
                processEnv.putAll(jobEnv);
            }
            Process p = pb.start();
            job.process(p);

            Thread out = pumpVirtual(p.getInputStream(), line -> {
                job.appendStdout(line);
                writeLine(stdoutW, line);
            });
            Thread err = pumpVirtual(p.getErrorStream(), line -> {
                job.appendStderr(line);
                writeLine(stderrW, line);
            });
            out.start();
            err.start();

            int code = p.waitFor();
            out.join();
            err.join();

            job.exitCode(code);
            if (job.status() != ExecJob.Status.KILLED) {
                job.status(code == 0 ? ExecJob.Status.COMPLETED : ExecJob.Status.FAILED);
            }
        } catch (Exception e) {
            log.warn("Exec job '{}' failed: {}", job.id(), e.toString());
            job.appendStderr("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.status(ExecJob.Status.FAILED);
        } finally {
            job.finishedAt(Instant.now());
            listener.stateChanged(job);
            cancelWatchdog(job.id());
            listener.finished(job);
        }
    }

    /**
     * Graceful process-<b>tree</b> termination. A job runs as {@code /bin/sh -c
     * "<command>"}, so {@code destroyForcibly()} on that shell alone would leave
     * its children (compiler, trainer, …) running orphaned. Instead: snapshot the
     * process + all descendants, send SIGTERM ({@link ProcessHandle#destroy}) to
     * each so a clean shutdown / checkpoint can run, then SIGKILL
     * ({@link ProcessHandle#destroyForcibly}) any survivor after the kill grace.
     * Snapshotting before signalling keeps reparented grandchildren targeted.
     * Non-blocking — the escalation is scheduled on the watchdog executor.
     */
    private void terminateTree(@Nullable Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>();
        p.descendants().forEach(tree::add);
        tree.add(p.toHandle());
        tree.forEach(ProcessHandle::destroy);
        long graceMs = config.killGraceMs();
        if (graceMs <= 0) {
            tree.forEach(ProcessHandle::destroyForcibly);
            return;
        }
        watchdog.schedule(
                () -> {
                    for (ProcessHandle h : tree) {
                        if (h.isAlive()) {
                            h.destroyForcibly();
                        }
                    }
                },
                graceMs,
                TimeUnit.MILLISECONDS);
    }

    // ──────────────────── Watchdog ────────────────────

    /**
     * Schedules (or reschedules) the watchdog kill for {@code job}. Cancels any
     * previous scheduled future so {@code extendDeadline} can't accumulate
     * parallel timers.
     */
    private void rearmWatchdog(ExecJob job, Instant deadline) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        ScheduledFuture<?> future = watchdog.schedule(() -> fireWatchdog(job), delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = watchdogFutures.put(job.id(), future);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    private void cancelWatchdog(String jobId) {
        ScheduledFuture<?> future = watchdogFutures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Watchdog scheduler callback. Re-checks the deadline because it might have
     * been extended after we were scheduled — in that case we re-arm at the new
     * deadline instead of killing. Otherwise we try to claim the kill atomically;
     * the worker's {@code finally} picks up the KILLED status and delivers the
     * completion (the listener sees {@link ExecJob#killedByWatchdog()}).
     */
    private void fireWatchdog(ExecJob job) {
        try {
            Instant currentDeadline = job.deadline();
            if (currentDeadline != null && currentDeadline.isAfter(Instant.now())) {
                rearmWatchdog(job, currentDeadline);
                return;
            }
            if (!job.attemptWatchdogKill()) {
                return;
            }
            terminateTree(job.process());
            log.info("Watchdog killed job '{}' (tree) after deadline expired", job.id());
        } catch (RuntimeException e) {
            log.warn("Watchdog fire for job '{}' threw: {}", job.id(), e.toString(), e);
        }
    }

    private static ThreadFactory watchdogThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "exec-watchdog-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ──────────────────── Output ────────────────────

    private static Thread pumpVirtual(InputStream in, Consumer<String> sink) {
        return Thread.ofVirtual().unstarted(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException ignored) {
                // stream closed — fine
            }
        });
    }

    private static void writeLine(BufferedWriter w, String line) {
        synchronized (w) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (IOException ignored) {
                // disk trouble — keep the process running; the inline buffer still holds the output
            }
        }
    }

    private static BufferedWriter openLog(Path file) throws IOException {
        return Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static long fileSize(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long fileMtime(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    // ──────────────────── Indexing ────────────────────

    private void indexJob(String scopeKey, ExecJob job) {
        Map<String, ExecJob> perScope =
                jobs.computeIfAbsent(scopeKey, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (perScope) {
            perScope.put(job.id(), job);
            if (perScope.size() <= config.maxJobsPerScope()) {
                return;
            }
            // Drop the oldest terminal job to stay under the cap.
            String victim = null;
            ExecJob victimJob = null;
            for (Map.Entry<String, ExecJob> e : perScope.entrySet()) {
                if (e.getValue().isTerminal()) {
                    victim = e.getKey();
                    victimJob = e.getValue();
                    break;
                }
            }
            if (victim != null) {
                perScope.remove(victim);
                listener.evicted(victimJob);
                // Reclaim the evicted job's on-disk log dir. Otherwise
                // <baseDir>/<scopeKey>/<jobId>/ accumulates unbounded for the
                // process lifetime (nothing else deletes it — these dirs live
                // outside workspace RootDirs, so teardown never reaches them).
                deleteJobDirQuietly(scopeKey, victim);
            }
        }
    }

    private Path jobDir(String scopeKey, String jobId) {
        return config.baseDir().toAbsolutePath().normalize().resolve(scopeKey).resolve(jobId);
    }

    /** Best-effort recursive delete of an evicted job's on-disk log directory. */
    private void deleteJobDirQuietly(String scopeKey, String jobId) {
        Path dir = jobDir(scopeKey, jobId);
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignore) {
                        // best-effort per entry
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            log.warn("ExecJobs: failed to delete evicted job dir {}: {}", dir, e.toString());
        }
    }
}
