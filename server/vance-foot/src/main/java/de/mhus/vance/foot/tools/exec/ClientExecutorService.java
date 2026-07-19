package de.mhus.vance.foot.tools.exec;

import jakarta.annotation.PreDestroy;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Local equivalent of the brain's {@code ExecManager}: runs shell
 * commands on the foot host with virtual-thread pumps and per-job
 * stdout/stderr log files. Singleton — every session connected to
 * this foot shares the same job index (it's the user's machine).
 *
 * <p>Storage layout: {@code data/client-exec/<jobId>/{stdout,stderr}.log},
 * relative to the foot's working directory. Logs survive a restart;
 * the live process does not. RUNNING jobs from a previous boot are
 * not auto-recovered (we'd be re-attaching to a process that's gone).
 */
@Service
@Slf4j
public class ClientExecutorService {

    private static final String BASE_DIR = "data/client-exec";
    private static final int MAX_JOBS = 32;

    private final Map<String, ClientExecJob> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Drives the optional hard-kill deadline. One scheduled task per
     * job so a kill won't impact unrelated jobs and so the future can
     * be cancelled cleanly on natural completion.
     */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(watchdogThreadFactory());
    private final Map<String, ScheduledFuture<?>> watchdogFutures = new ConcurrentHashMap<>();

    /** Lazy — dispatcher in turn depends on ConnectionService which depends on this one indirectly. */
    private final ObjectProvider<FootExecEventDispatcher> dispatcher;

    private final de.mhus.vance.foot.permission.PermissionService permissions;

    public ClientExecutorService(ObjectProvider<FootExecEventDispatcher> dispatcher,
                                 de.mhus.vance.foot.permission.PermissionService permissions) {
        this.dispatcher = dispatcher;
        this.permissions = permissions;
    }

    public ClientExecJob submit(String command) {
        return submit(command, null, null, null);
    }

    public ClientExecJob submit(
            String command, @Nullable String sessionId, @Nullable String projectId) {
        return submit(command, sessionId, projectId, null);
    }

    /**
     * Spawns {@code command} and tags the resulting job with the current
     * session-bind, if any, so the brain registry can later filter by
     * project/session. Pass {@code null} for both scope fields when the
     * caller is the local CLI (no active brain bind).
     *
     * <p>When {@code deadline} is non-null, a simple hard-kill watchdog
     * is armed: if the subprocess is still RUNNING when the instant
     * passes, the foot kills it forcibly and marks the job
     * {@code timedOut=true}. Unlike the brain-side variant, the foot
     * has no extend / lease API — pass the full intended timeout up
     * front.
     */
    public ClientExecJob submit(
            String command,
            @Nullable String sessionId,
            @Nullable String projectId,
            @Nullable Instant deadline) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Path jobDir = Path.of(BASE_DIR).toAbsolutePath().normalize().resolve(jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ClientExecException(
                    "Cannot create exec job dir: " + e.getMessage(), e);
        }
        ClientExecJob job = new ClientExecJob(
                jobId, command,
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"),
                sessionId, projectId);
        if (deadline != null) {
            job.deadline(deadline);
        }
        index(job);
        notifyStarted(job);
        workers.submit(() -> runJob(job));
        if (deadline != null) {
            armWatchdog(job, deadline);
        }
        return job;
    }

    private void notifyStarted(ClientExecJob job) {
        FootExecEventDispatcher d = dispatcher.getIfAvailable();
        if (d != null) d.publishStarted(job);
    }

    private void notifyEnded(ClientExecJob job) {
        FootExecEventDispatcher d = dispatcher.getIfAvailable();
        if (d != null) d.publishEnded(job);
    }

    /**
     * Visible for {@link FootExecEventDispatcher} to build the snapshot
     * payload at connect-time.
     */
    Collection<ClientExecJob> snapshot() {
        synchronized (jobs) {
            return new java.util.ArrayList<>(jobs.values());
        }
    }

    public ClientExecJob waitFor(ClientExecJob job, long maxMillis) {
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

    public Optional<ClientExecJob> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Collection<ClientExecJob> list() {
        synchronized (jobs) {
            return new java.util.ArrayList<>(jobs.values());
        }
    }

    /** Compact status snapshot — no stdout/stderr bodies. */
    public Optional<ClientExecStat> stat(String id) {
        return get(id).map(ClientExecutorService::toStat);
    }

    static ClientExecStat toStat(ClientExecJob job) {
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        return new ClientExecStat(
                job.id(),
                job.command(),
                job.sessionId(),
                job.projectId(),
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
     * Last {@code n} lines of the requested stream. Reads from the
     * persisted log file. Returns oldest-first.
     */
    public List<String> tail(String id, int n, Stream stream) {
        if (n <= 0) return List.of();
        ClientExecJob job = jobs.get(id);
        if (job == null) {
            throw new ClientExecException("Unknown client exec job: '" + id + "'", null);
        }
        Path file = stream == Stream.STDERR ? job.stderrFile() : job.stdoutFile();
        return tailFile(file, n);
    }

    static List<String> tailFile(Path file, int n) {
        if (!Files.isReadable(file)) return List.of();
        Deque<String> ring = new ArrayDeque<>(n);
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (ring.size() == n) ring.removeFirst();
                ring.addLast(line);
            }
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(ring);
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
            return Files.isRegularFile(file)
                    ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Selector for {@link #tail}. */
    public enum Stream { STDOUT, STDERR }

    public boolean kill(String id) {
        ClientExecJob job = jobs.get(id);
        if (job == null || job.isTerminal()) return false;
        Process p = job.process();
        if (p == null) return false;
        job.status(ClientExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        terminateTree(p);
        cancelWatchdog(id);
        notifyEnded(job);
        return true;
    }

    /** SIGTERM/SIGKILL grace on the local machine (mirror of the Brain default). */
    private static final long KILL_GRACE_MS = 10_000;

    /**
     * Graceful process-<b>tree</b> termination. A job runs as a shell command,
     * so destroying only that shell would leave its children (build/train
     * subprocesses) orphaned on the user's machine. Snapshot the process + all
     * descendants, SIGTERM ({@link ProcessHandle#destroy}) each, then SIGKILL
     * ({@link ProcessHandle#destroyForcibly}) survivors after {@link #KILL_GRACE_MS}.
     * Non-blocking — escalation runs on the watchdog scheduler. Mirrors
     * {@code ExecManager.terminateTree} (see work-target.md).
     */
    private void terminateTree(@Nullable Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>();
        p.descendants().forEach(tree::add);
        tree.add(p.toHandle());
        tree.forEach(ProcessHandle::destroy);
        if (KILL_GRACE_MS <= 0) {
            tree.forEach(ProcessHandle::destroyForcibly);
            return;
        }
        watchdog.schedule(() -> {
            for (ProcessHandle h : tree) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
        }, KILL_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Immediate forcible tree kill (no grace) — for JVM shutdown, where a
     * deferred escalation on the watchdog scheduler would not run.
     */
    private static void destroyTreeNow(@Nullable Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>();
        p.descendants().forEach(tree::add);
        tree.add(p.toHandle());
        tree.forEach(ProcessHandle::destroyForcibly);
    }

    // ──────────────────── Watchdog ────────────────────

    private void armWatchdog(ClientExecJob job, Instant deadline) {
        long delayMs = Math.max(0,
                Duration.between(Instant.now(), deadline).toMillis());
        ScheduledFuture<?> future = watchdog.schedule(
                () -> fireWatchdog(job), delayMs, TimeUnit.MILLISECONDS);
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

    private void fireWatchdog(ClientExecJob job) {
        try {
            if (!job.attemptWatchdogKill()) {
                return;
            }
            terminateTree(job.process());
            log.info("Client watchdog killed job '{}' (tree) after deadline expired", job.id());
        } catch (RuntimeException e) {
            log.warn("Client watchdog fire for job '{}' threw: {}", job.id(), e.toString(), e);
        }
    }

    private static ThreadFactory watchdogThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "client-exec-watchdog-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ──────────────────── Runner ────────────────────

    private void runJob(ClientExecJob job) {
        try (BufferedWriter stdoutW = openLog(job.stdoutFile());
             BufferedWriter stderrW = openLog(job.stderrFile())) {

            ProcessBuilder pb = new ProcessBuilder(buildArgv(job.command()));
            pb.redirectErrorStream(false);
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
            if (job.status() != ClientExecJob.Status.KILLED) {
                job.status(code == 0 ? ClientExecJob.Status.COMPLETED
                        : ClientExecJob.Status.FAILED);
            }
        } catch (Exception e) {
            log.warn("Client exec job '{}' failed: {}", job.id(), e.toString());
            job.appendStderr("ERROR: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.status(ClientExecJob.Status.FAILED);
        } finally {
            job.finishedAt(Instant.now());
            cancelWatchdog(job.id());
            notifyEnded(job);
        }
    }

    private static Thread pumpVirtual(InputStream in, Consumer<String> sink) {
        return Thread.ofVirtual().unstarted(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
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
                // disk trouble — process keeps running, inline buffer holds output
            }
        }
    }

    private static BufferedWriter openLog(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ──────────────────── Indexing ────────────────────

    private void index(ClientExecJob job) {
        synchronized (jobs) {
            jobs.put(job.id(), job);
            if (jobs.size() > MAX_JOBS) {
                String victim = null;
                for (Map.Entry<String, ClientExecJob> e : jobs.entrySet()) {
                    if (e.getValue().isTerminal()) {
                        victim = e.getKey();
                        break;
                    }
                }
                if (victim != null) {
                    jobs.remove(victim);
                }
            }
        }
    }

    /**
     * Builds the process argv for {@code command}. With exec-isolation
     * active the command is wrapped in the configured isolation tool;
     * otherwise it runs under the platform shell ({@code /bin/sh -c} /
     * {@code cmd.exe /c}).
     */
    private List<String> buildArgv(String command) {
        de.mhus.vance.foot.permission.ExecIsolation isolation = permissions.isolation();
        if (isolation.enabled()) {
            List<String> argv = isolation.wrap(command);
            log.info("exec isolation: wrapping command in '{}'",
                    argv.isEmpty() ? "?" : argv.get(0));
            return argv;
        }
        return isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @PreDestroy
    void shutdown() {
        synchronized (jobs) {
            for (ClientExecJob j : jobs.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    destroyTreeNow(j.process());
                }
            }
        }
        workers.shutdownNow();
        watchdog.shutdownNow();
        watchdogFutures.clear();
    }

    /** Thrown when the executor itself can't get a job started (filesystem, etc.). */
    public static class ClientExecException extends RuntimeException {
        public ClientExecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
