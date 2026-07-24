package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Runs shell commands in background virtual threads with live output
 * persistence. Jobs are scoped per <em>project</em>: the outer index
 * is {@code projectId}, the inner is {@code jobId}, so one project
 * cannot see another's jobs by stumbling on an id, while sessions in
 * the same project share their job view (a long build started in one
 * session is visible to the next session in the same project).
 *
 * <p>Working directory is the caller project's workspace root, so
 * {@code git clone …} lands where {@code workspace_*} can read it.
 *
 * <p>Output flows two ways on purpose: line-by-line into a log file
 * (complete record, readable after truncation), and into an in-memory
 * {@link StringBuilder} (fast inline response). The inline buffer is
 * reset-free — we trim it later in the tool layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecManager {

    private final ExecProperties properties;
    private final WorkspaceService workspaceService;
    private final ExecutionRegistryService registry;
    /**
     * {@link ObjectProvider} keeps the dependency lazy — the router
     * pulls in {@code ProcessEventEmitter → ThinkEngineService → tools}
     * which transitively reaches every {@code Tool} bean, including
     * the ones backed by this manager. Direct injection would close
     * the cycle.
     */
    private final ObjectProvider<EngineMessageRouter> engineMessageRouterProvider;

    private final Map<String, Map<String, ExecJob>> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Drives deadline-based kills. One scheduled task per job, keyed
     * by jobId so {@link #extendDeadline} can cancel and reschedule
     * cleanly. Single daemon thread is enough — the fire-callback
     * dispatches the actual kill, which is a few sub-millisecond
     * map lookups plus {@code Process.destroyForcibly()}.
     */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(watchdogThreadFactory());
    private final Map<String, ScheduledFuture<?>> watchdogFutures = new ConcurrentHashMap<>();

    // ──────────────────── Public API ────────────────────

    /**
     * Submits {@code command} and returns the job. The job starts
     * immediately in the named workspace RootDir; the caller can
     * observe progress via {@link #waitFor} or later {@link #get}
     * calls.
     */
    public ExecJob submit(String tenantId, String projectId, String dirName, String command) {
        return submit(tenantId, projectId, null, dirName, command, SubmitOptions.defaults());
    }

    /**
     * Variant that binds the job to an owning think-process. When set,
     * the job's natural terminal transition triggers a {@code
     * ProcessEvent(EXEC_FINISHED)} delivery to that process's inbox so
     * the LLM doesn't have to poll {@code work_exec_status}. See
     * {@code planning/wakeup-and-exec.md} §4.2.
     */
    public ExecJob submit(
            String tenantId,
            String projectId,
            @Nullable String ownerProcessId,
            String dirName,
            String command) {
        return submit(tenantId, projectId, ownerProcessId, dirName, command,
                SubmitOptions.defaults());
    }

    /**
     * Legacy overload kept for direct callers that only carry a deadline.
     */
    public ExecJob submit(
            String tenantId,
            String projectId,
            @Nullable String ownerProcessId,
            String dirName,
            String command,
            @Nullable Instant deadline) {
        return submit(tenantId, projectId, ownerProcessId, dirName, command,
                SubmitOptions.withDeadline(deadline));
    }

    /**
     * Full-options variant. {@code options} bundles deadline, sealed
     * subprocess env, and labels — see {@link SubmitOptions}. When
     * {@code env} is non-null the runner wipes inherited JVM vars and
     * installs only these (security boundary for script-execution
     * paths). Labels are exposed via {@link ExecJob#labels()} and copied
     * onto the {@link
     * de.mhus.vance.brain.execution.ExecutionRegistryEntry} when the
     * caller goes through {@code submitTracked} / {@code
     * submitTrackedAndRender}.
     */
    public ExecJob submit(
            String tenantId,
            String projectId,
            @Nullable String ownerProcessId,
            String dirName,
            String command,
            SubmitOptions options) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path cwd = resolveCwd(tenantId, projectId, dirName);
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        String scopeKey = scopeKey(tenantId, projectId);
        Path jobDir = jobDir(scopeKey, jobId);
        try {
            Files.createDirectories(jobDir);
        } catch (IOException e) {
            throw new ExecException(
                    "Cannot create exec job dir: " + e.getMessage(), e);
        }
        ExecJob job = new ExecJob(
                jobId, projectId, ownerProcessId, command,
                jobDir.resolve("stdout.log"),
                jobDir.resolve("stderr.log"),
                options.env(),
                options.labels());
        Instant deadline = options.deadline();
        if (deadline != null) {
            job.initialDeadline(deadline);
        }
        indexJob(scopeKey, job);
        workers.submit(() -> runJob(job, cwd));
        if (deadline != null) {
            rearmWatchdog(job, deadline);
        }
        return job;
    }

    /**
     * Pushes the deadline of {@code jobId} out by {@code extension}
     * from now. Returns {@code false} when the job is unknown or no
     * longer RUNNING (e.g. the natural-completion / watchdog-kill
     * already happened — the caller's {@code work_exec_check} will see
     * the terminal status reflected on the next read).
     */
    public boolean extendDeadline(
            String tenantId, String projectId, String jobId, Duration extension) {
        if (extension == null || extension.isNegative() || extension.isZero()) {
            throw new ExecException("extension must be positive");
        }
        ExecJob job = get(tenantId, projectId, jobId).orElse(null);
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

    private Path resolveCwd(String tenantId, String projectId, String dirName) {
        try {
            RootDirHandle handle = workspaceService.getRootDir(tenantId, projectId, dirName)
                    .orElseThrow(() -> new ExecException(
                            "Unknown workspace RootDir: "
                                    + tenantId + "/" + projectId + "/" + dirName));
            return handle.getPath();
        } catch (WorkspaceException e) {
            throw new ExecException(e.getMessage(), e);
        }
    }

    private static String scopeKey(String tenantId, String projectId) {
        return tenantId + "/" + projectId;
    }

    /**
     * Convenience for tools that want the full submit-track-wait-render
     * pipeline without having to touch the internal {@link ExecJob}
     * type: starts the command in the named RootDir, registers it with
     * {@link ExecutionRegistryService}, waits up to {@code waitMs} for
     * completion, and returns the renderer's response map (same shape
     * as {@code work_exec_run}).
     */
    public Map<String, Object> submitTrackedAndRender(
            String tenantId, String projectId,
            @Nullable String sessionId, @Nullable String processId,
            String dirName, String command, long waitMs) {
        return submitTrackedAndRender(tenantId, projectId, sessionId, processId,
                dirName, command, waitMs, SubmitOptions.defaults());
    }

    /** Full-options variant of {@link #submitTrackedAndRender}. */
    public Map<String, Object> submitTrackedAndRender(
            String tenantId, String projectId,
            @Nullable String sessionId, @Nullable String processId,
            String dirName, String command, long waitMs,
            SubmitOptions options) {
        return submitTrackedAndRender(tenantId, projectId, sessionId, processId,
                dirName, command, waitMs, options, null);
    }

    /**
     * As above, but {@code onJobId} is invoked with the freshly-created job's id
     * right after submit (before the blocking wait) — lets a caller surface the
     * job id for live progress (e.g. an async compose run's tail) while it runs.
     */
    public Map<String, Object> submitTrackedAndRender(
            String tenantId, String projectId,
            @Nullable String sessionId, @Nullable String processId,
            String dirName, String command, long waitMs,
            SubmitOptions options, @Nullable Consumer<String> onJobId) {
        ExecJob job = submit(tenantId, projectId, processId, dirName, command, options);
        if (onJobId != null) {
            onJobId.accept(job.id());
        }
        registry.register(new de.mhus.vance.brain.execution.ExecutionRegistryEntry(
                job.id(),
                de.mhus.vance.brain.execution.ExecutionOwner.Brain.INSTANCE,
                tenantId,
                projectId,
                sessionId,
                processId,
                job.command(),
                dirName,
                job.startedAt(),
                job.lastOutputAt(),
                null,
                de.mhus.vance.brain.execution.ExecutionStatus.RUNNING,
                null,
                job.stdoutFile().toString(),
                job.stderrFile().toString(),
                job.labels()));
        waitFor(job, waitMs);
        return ExecJobRenderer.render(job, properties.getInlineOutputCharCap());
    }

    /**
     * Async submit for REST-style callers (e.g. PythonCortexController).
     * Returns the job id immediately after submit + registry register;
     * the caller polls {@link #renderJob} to surface stdout/stderr +
     * terminal state to the user. Mirrors the
     * {@link #submitTrackedAndRender} setup minus the synchronous
     * wait+render — REST clients can't block.
     */
    public String submitTracked(
            String tenantId, String projectId,
            @Nullable String sessionId, @Nullable String processId,
            String dirName, String command) {
        return submitTracked(tenantId, projectId, sessionId, processId,
                dirName, command, SubmitOptions.defaults());
    }

    /** Full-options variant of {@link #submitTracked}. */
    public String submitTracked(
            String tenantId, String projectId,
            @Nullable String sessionId, @Nullable String processId,
            String dirName, String command,
            SubmitOptions options) {
        ExecJob job = submit(tenantId, projectId, processId, dirName, command, options);
        registry.register(new de.mhus.vance.brain.execution.ExecutionRegistryEntry(
                job.id(),
                de.mhus.vance.brain.execution.ExecutionOwner.Brain.INSTANCE,
                tenantId,
                projectId,
                sessionId,
                processId,
                job.command(),
                dirName,
                job.startedAt(),
                job.lastOutputAt(),
                null,
                de.mhus.vance.brain.execution.ExecutionStatus.RUNNING,
                null,
                job.stdoutFile().toString(),
                job.stderrFile().toString(),
                job.labels()));
        return job.id();
    }

    /**
     * Public snapshot for a previously-submitted job. Returns the
     * same {@code Map<String, Object>} shape as the LLM-facing
     * {@code work_exec_status} / {@code work_exec_run} tools — REST clients
     * read {@code status} / {@code stdout} / {@code stderr} /
     * {@code exitCode} / {@code durationMs}. Empty when the job id
     * doesn't belong to this project (or has been evicted).
     */
    public Optional<Map<String, Object>> renderJob(
            String tenantId, String projectId, String jobId) {
        return get(tenantId, projectId, jobId)
                .map(j -> ExecJobRenderer.render(j, properties.getInlineOutputCharCap()));
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

    /** Look up a job by tenant + project + id. */
    public Optional<ExecJob> get(String tenantId, String projectId, String jobId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        if (perProject == null) return Optional.empty();
        return Optional.ofNullable(perProject.get(jobId));
    }

    /** All jobs for a project, insertion-ordered (oldest first). */
    public Collection<ExecJob> listByProject(String tenantId, String projectId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        return perProject == null ? java.util.List.of() : perProject.values();
    }

    /** Compact status snapshot — no stdout/stderr bodies. */
    public Optional<ExecStat> stat(String tenantId, String projectId, String jobId) {
        return get(tenantId, projectId, jobId).map(ExecManager::toStat);
    }

    static ExecStat toStat(ExecJob job) {
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
     * Last {@code n} lines of the requested stream. Reads from the
     * persisted log file so callers see the same content {@code tail -n}
     * would on the host. Returns the lines oldest-first (chronological).
     */
    public List<String> tail(
            String tenantId, String projectId, String jobId, int n, Stream stream) {
        if (n <= 0) return List.of();
        ExecJob job = get(tenantId, projectId, jobId).orElseThrow(() ->
                new ExecException("Unknown exec job: '" + jobId + "' (not in this project)"));
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

    /** Force-kill a still-running job. Returns {@code false} if already terminal. */
    public boolean kill(String tenantId, String projectId, String jobId) {
        ExecJob job = get(tenantId, projectId, jobId).orElse(null);
        if (job == null || job.isTerminal()) return false;
        Process p = job.process();
        if (p == null) return false;
        job.status(ExecJob.Status.KILLED);
        job.finishedAt(Instant.now());
        terminateTree(p);
        cancelWatchdog(jobId);
        notifyRegistry(job);
        return true;
    }

    /**
     * Force-kills every running job for a project — used by
     * {@code ProjectManagerService.archive(...)} once that lands.
     */
    public int killAllForProject(String tenantId, String projectId) {
        Map<String, ExecJob> perProject = jobs.get(scopeKey(tenantId, projectId));
        if (perProject == null) return 0;
        int killed = 0;
        synchronized (perProject) {
            for (ExecJob j : perProject.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.status(ExecJob.Status.KILLED);
                    j.finishedAt(Instant.now());
                    terminateTree(j.process());
                    cancelWatchdog(j.id());
                    notifyRegistry(j);
                    killed++;
                }
            }
        }
        return killed;
    }

    /**
     * Graceful process-<b>tree</b> termination. A job runs as {@code /bin/sh -c
     * "<command>"}, so {@code destroyForcibly()} on that shell alone would leave
     * its children (compiler, trainer, …) running orphaned. Instead: snapshot
     * the process + all descendants, send SIGTERM ({@link ProcessHandle#destroy})
     * to each so a clean shutdown / checkpoint can run, then SIGKILL
     * ({@link ProcessHandle#destroyForcibly}) any survivor after
     * {@code killGraceMs}. Snapshotting before signalling keeps reparented
     * grandchildren targeted. Non-blocking — the escalation is scheduled on the
     * watchdog executor.
     */
    private void terminateTree(@Nullable Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>();
        p.descendants().forEach(tree::add);
        tree.add(p.toHandle());
        tree.forEach(ProcessHandle::destroy);
        long graceMs = properties.getKillGraceMs();
        if (graceMs <= 0) {
            tree.forEach(ProcessHandle::destroyForcibly);
            return;
        }
        watchdog.schedule(() -> {
            for (ProcessHandle h : tree) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
        }, graceMs, TimeUnit.MILLISECONDS);
    }

    // ──────────────────── Runner ────────────────────

    private void runJob(ExecJob job, Path cwd) {
        try (BufferedWriter stdoutW = openLog(job.stdoutFile());
             BufferedWriter stderrW = openLog(job.stderrFile())) {

            ProcessBuilder pb = new ProcessBuilder(buildArgv(job.command(), cwd));
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Map<String, String> sealedEnv = job.env();
            if (sealedEnv != null) {
                Map<String, String> processEnv = pb.environment();
                processEnv.clear();
                processEnv.putAll(sealedEnv);
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
            job.appendStderr("ERROR: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.status(ExecJob.Status.FAILED);
        } finally {
            job.finishedAt(Instant.now());
            notifyRegistry(job);
            cancelWatchdog(job.id());
            pushCompletionIfTracked(job);
        }
    }

    // ──────────────────── Watchdog ────────────────────

    /**
     * Schedules (or reschedules) the watchdog kill for {@code job}.
     * Cancels any previous scheduled future so {@code extendDeadline}
     * can't accumulate parallel timers.
     */
    private void rearmWatchdog(ExecJob job, Instant deadline) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
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

    /**
     * Watchdog scheduler callback. Re-checks the deadline because it
     * might have been extended after we were scheduled — in that case
     * we re-arm at the new deadline instead of killing. Otherwise we
     * try to claim the kill atomically; the worker's {@code finally}
     * picks up the KILLED status and pushes
     * {@link ProcessEventType#EXEC_TIMEOUT}.
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

    /**
     * Pushes a {@code ProcessEvent(EXEC_FINISHED)} to the owning
     * think-process's inbox after natural completion (the worker
     * thread reached its {@code finally} block, regardless of exit
     * status — COMPLETED, FAILED, or KILLED via the
     * {@link #kill(String, String, String)} path that flips status
     * outside the worker). The event lets the LLM react to job
     * termination without polling {@code work_exec_status}.
     *
     * <p>No-op when the job has no owner (background submitter that
     * doesn't care about completion delivery — e.g. internal Python
     * tooling that uses inline output exclusively).
     *
     * <p>Watchdog-driven kills will use {@code EXEC_TIMEOUT} instead
     * (Phase 3 of {@code planning/wakeup-and-exec.md}) — see that
     * doc's §4.2 for the event-type contract.
     */
    private void pushCompletionIfTracked(ExecJob job) {
        String ownerProcessId = job.ownerProcessId();
        if (ownerProcessId == null || ownerProcessId.isBlank()) {
            return;
        }
        boolean timedOut = job.killedByWatchdog();
        ProcessEventType eventType = timedOut
                ? ProcessEventType.EXEC_TIMEOUT
                : ProcessEventType.EXEC_FINISHED;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", job.id());
            payload.put("status", job.status().name());
            if (job.exitCode() != null) {
                payload.put("exitCode", job.exitCode());
            }
            Instant finished = job.finishedAt();
            if (finished != null) {
                payload.put("finishedAt", finished.toString());
            }
            payload.put("projectId", job.projectId());
            payload.put("stdoutTail", tailFile(job.stdoutFile(),
                    properties.getCompletionTailLines()));
            payload.put("stderrTail", tailFile(job.stderrFile(),
                    properties.getCompletionTailLines()));
            if (timedOut) {
                long runMs = Duration.between(job.startedAt(),
                        finished != null ? finished : Instant.now()).toMillis();
                payload.put("killedAfterSeconds", runMs / 1000);
            }

            String summary = timedOut
                    ? "Exec " + job.id() + " timed out"
                    : "Exec " + job.id() + " " + job.status().name().toLowerCase();
            PendingMessageDocument doc = PendingMessageDocument.builder()
                    .type(PendingMessageType.PROCESS_EVENT)
                    .at(Instant.now())
                    .sourceProcessId(ownerProcessId)
                    .eventType(eventType)
                    .content(summary)
                    .payload(payload)
                    .eventId(java.util.UUID.randomUUID().toString())
                    .build();
            boolean ok = engineMessageRouterProvider.getObject()
                    .dispatch(ownerProcessId, ownerProcessId, doc);
            if (!ok) {
                log.warn("{} dispatch dropped owner='{}' job='{}'",
                        eventType, ownerProcessId, job.id());
            }
        } catch (RuntimeException e) {
            log.warn("{} dispatch failed owner='{}' job='{}': {}",
                    eventType, ownerProcessId, job.id(), e.toString(), e);
        }
    }

    /** Mirror the job's terminal state into the cross-side registry. */
    private void notifyRegistry(ExecJob job) {
        registry.updateProgress(
                job.id(),
                job.lastOutputAt(),
                toRegistryStatus(job.status()),
                job.exitCode(),
                job.finishedAt());
    }

    static ExecutionStatus toRegistryStatus(ExecJob.Status s) {
        return switch (s) {
            case RUNNING -> ExecutionStatus.RUNNING;
            case COMPLETED -> ExecutionStatus.COMPLETED;
            case FAILED -> ExecutionStatus.FAILED;
            case KILLED -> ExecutionStatus.KILLED;
            case ORPHANED -> ExecutionStatus.ORPHANED;
        };
    }

    // ──────────────────── Orphan reconciliation ────────────────────

    /**
     * Reconciles jobs stuck in {@code RUNNING} because their worker thread
     * died without running its {@code finally} (a hard Error / thread-death
     * that skipped the terminal transition + registry mirror). Such a job
     * would otherwise stay RUNNING for the pod's lifetime, which pins its
     * session alive via {@link
     * ExecutionRegistryService#hasActiveJobsForSession} (blocking the
     * IdleSweeper) and leaves an owning think-process waiting for a wakeup
     * that never comes.
     *
     * <p>Pod-local by design: {@link #jobs} and the registry are in-memory
     * per pod, and the {@code Process} liveness handle only exists in this
     * JVM — so this runs on <em>every</em> pod (never cluster-master-gated).
     * A stuck job is completed as {@link ExecJob.Status#ORPHANED}, then
     * mirrored to the registry (status → terminal, session no longer pinned)
     * and its owner is notified.
     *
     * <p>False-positive-safe: a job is only reconciled when its OS process
     * is dead/absent <em>and</em> it has shown no output for longer than
     * {@link ExecProperties#getOrphanReconcileTtl()}. A legitimately
     * long-running but quiet job has a live process and is never touched.
     *
     * @return the number of jobs reconciled to ORPHANED this pass
     */
    public int reconcileOrphanedJobs(Instant now) {
        Duration ttl = properties.getOrphanReconcileTtl();
        // Collect candidates under each per-project monitor (required for
        // safe iteration of the synchronizedMap), then act outside the lock
        // so notifyRegistry / owner-push I/O never blocks submit/indexJob.
        List<ExecJob> candidates = new ArrayList<>();
        for (Map<String, ExecJob> perProject : jobs.values()) {
            synchronized (perProject) {
                for (ExecJob job : perProject.values()) {
                    if (isStuckOrphan(job, now, ttl)) {
                        candidates.add(job);
                    }
                }
            }
        }
        int reconciled = 0;
        for (ExecJob job : candidates) {
            // Re-check atomically: a real finally may have terminated the
            // job between collection and now — then this is a no-op.
            if (!job.markOrphanedIfRunning()) {
                continue;
            }
            log.warn("Reconciled orphaned exec job '{}' (RUNNING with dead process, "
                    + "no output for >{}) → ORPHANED", job.id(), ttl);
            notifyRegistry(job);
            cancelWatchdog(job.id());
            pushCompletionIfTracked(job);
            reconciled++;
        }
        return reconciled;
    }

    /**
     * A job is a stuck orphan when it is still RUNNING, its OS process is
     * dead or was never assigned (the worker never reached {@code
     * pb.start()} or died right after the process exited), and it has been
     * silent past the TTL. The silence dwell also covers the tiny window
     * between {@code Process.waitFor()} returning and the worker setting the
     * terminal status, so a normally-completing job is never misclassified.
     */
    static boolean isStuckOrphan(ExecJob job, Instant now, Duration ttl) {
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
                // disk trouble — keep the process running; inline buffer still holds output
            }
        }
    }

    private static BufferedWriter openLog(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ──────────────────── Indexing ────────────────────

    private void indexJob(String scopeKey, ExecJob job) {
        Map<String, ExecJob> perProject = jobs.computeIfAbsent(
                scopeKey, k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (perProject) {
            perProject.put(job.id(), job);
            int cap = properties.getMaxJobsPerProject();
            if (perProject.size() > cap) {
                // Drop the oldest terminal job to stay under cap.
                String victim = null;
                for (Map.Entry<String, ExecJob> e : perProject.entrySet()) {
                    if (e.getValue().isTerminal()) {
                        victim = e.getKey();
                        break;
                    }
                }
                if (victim != null) {
                    perProject.remove(victim);
                    // Evict the mirrored registry entry too — otherwise the
                    // Brain-owned ExecutionRegistryEntry outlives the job and
                    // leaks for the pod's lifetime (code-review Phase 2).
                    registry.removeById(victim);
                }
            }
        }
    }

    private Path jobDir(String scopeKey, String jobId) {
        return Path.of(properties.getBaseDir()).toAbsolutePath().normalize()
                .resolve(scopeKey).resolve(jobId);
    }

    private static void requireTenant(@Nullable String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ExecException("Exec tools require a tenant scope");
        }
    }

    private static void requireProject(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new ExecException("Exec tools require a project scope");
        }
    }

    /**
     * Builds the process argv for {@code command}. With exec-isolation
     * enabled the command is wrapped in the configured tool (the job's
     * RootDir {@code cwd} fills {@code {workdir}}); otherwise it runs under
     * the platform shell.
     */
    private List<String> buildArgv(String command, Path cwd) {
        ExecProperties.Isolation iso = properties.getIsolation();
        if (ExecIsolation.enabled(iso)) {
            List<String> argv = ExecIsolation.wrap(iso.getWrapper(), cwd.toString(), command);
            log.info("exec isolation: wrapping work_exec command in '{}'",
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
        for (Map<String, ExecJob> perProject : jobs.values()) {
            for (ExecJob j : perProject.values()) {
                if (!j.isTerminal() && j.process() != null) {
                    j.process().destroyForcibly();
                }
            }
        }
        workers.shutdownNow();
        watchdog.shutdownNow();
        watchdogFutures.clear();
    }
}
