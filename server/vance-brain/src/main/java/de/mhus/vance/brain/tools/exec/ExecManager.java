package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecCompletionEvent;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.vault.ScriptSecretAccumulator;
import de.mhus.vance.exec.ExecException;
import de.mhus.vance.exec.ExecIsolation;
import de.mhus.vance.exec.ExecJob;
import de.mhus.vance.exec.ExecJobs;
import de.mhus.vance.exec.ExecStat;
import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.SubmitOptions;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Runs shell commands in background virtual threads with live output
 * persistence. Jobs are scoped per <em>project</em>: the outer index
 * is {@code tenant/project}, the inner is {@code jobId}, so one project
 * cannot see another's jobs by stumbling on an id, while sessions in
 * the same project share their job view (a long build started in one
 * session is visible to the next session in the same project).
 *
 * <p>Working directory is the caller project's workspace root, so
 * {@code git clone …} lands where {@code workspace_*} can read it.
 *
 * <p>The job engine itself ({@link ExecJobs} in {@code vance-exec}: lifecycle,
 * output files, process-tree kill, watchdog, eviction, orphan detection) is
 * plain Java, shared with the runner. This bean is the brain around it: the
 * workspace gate and RootDir lookup, the sealed per-scope environment, the
 * execution registry that mirrors job state, and the {@code ProcessEvent} that
 * wakes the owning think-process when a job ends.
 */
@Service
@Slf4j
public class ExecManager {

    private final ExecProperties properties;
    private final ExecEnvironmentBuilder environmentBuilder;
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

    private final ExecJobs jobs;

    public ExecManager(
            ExecProperties properties,
            ExecEnvironmentBuilder environmentBuilder,
            WorkspaceService workspaceService,
            ExecutionRegistryService registry,
            ObjectProvider<EngineMessageRouter> engineMessageRouterProvider) {
        this.properties = properties;
        this.environmentBuilder = environmentBuilder;
        this.workspaceService = workspaceService;
        this.registry = registry;
        this.engineMessageRouterProvider = engineMessageRouterProvider;
        this.jobs = new ExecJobs(
                new ExecJobs.Config(
                        Path.of(properties.getBaseDir()),
                        properties.getKillGraceMs(),
                        properties.getMaxJobsPerProject(),
                        properties.getOrphanReconcileTtl()),
                new JobListener());
    }

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
            String tenantId, String projectId, @Nullable String ownerProcessId, String dirName, String command) {
        return submit(tenantId, projectId, ownerProcessId, dirName, command, SubmitOptions.defaults());
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
        return submit(tenantId, projectId, ownerProcessId, dirName, command, SubmitOptions.withDeadline(deadline));
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
        // Every exec job — agent tools, workflow shell tasks, compose, Python
        // Cortex — funnels through here, so this is the one gate for running
        // programs on a pod without workspace access.
        if (!workspaceService.mode().accessible()) {
            throw new ExecException("Running programs is not available: this server has no workspace access"
                    + " (vance.workspace.mode=off)");
        }
        Path cwd = resolveCwd(tenantId, projectId, dirName);
        return jobs.submit(new ExecJobs.Submission(
                scopeKey(tenantId, projectId),
                projectId,
                ownerProcessId,
                command,
                buildArgv(command, cwd),
                cwd,
                environmentBuilder.build(tenantId, projectId, options.userId(), options.env()),
                options.labels(),
                options.deadline()));
    }

    /**
     * Pushes the deadline of {@code jobId} out by {@code extension}
     * from now. Returns {@code false} when the job is unknown or no
     * longer RUNNING (e.g. the natural-completion / watchdog-kill
     * already happened — the caller's {@code work_exec_check} will see
     * the terminal status reflected on the next read).
     */
    public boolean extendDeadline(String tenantId, String projectId, String jobId, Duration extension) {
        return jobs.extendDeadline(scopeKey(tenantId, projectId), jobId, extension);
    }

    private Path resolveCwd(String tenantId, String projectId, String dirName) {
        try {
            RootDirHandle handle = workspaceService
                    .getRootDir(tenantId, projectId, dirName)
                    .orElseThrow(() -> new ExecException(
                            "Unknown workspace RootDir: " + tenantId + "/" + projectId + "/" + dirName));
            return handle.getPath();
        } catch (WorkspaceException e) {
            throw new ExecException(e.getMessage(), e);
        }
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
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String dirName,
            String command,
            long waitMs) {
        return submitTrackedAndRender(
                tenantId, projectId, sessionId, processId, dirName, command, waitMs, SubmitOptions.defaults());
    }

    /** Full-options variant of {@link #submitTrackedAndRender}. */
    public Map<String, Object> submitTrackedAndRender(
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String dirName,
            String command,
            long waitMs,
            SubmitOptions options) {
        return submitTrackedAndRender(
                tenantId, projectId, sessionId, processId, dirName, command, waitMs, options, null);
    }

    /**
     * As above, but {@code onJobId} is invoked with the freshly-created job's id
     * right after submit (before the blocking wait) — lets a caller surface the
     * job id for live progress (e.g. an async compose run's tail) while it runs.
     */
    public Map<String, Object> submitTrackedAndRender(
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String dirName,
            String command,
            long waitMs,
            SubmitOptions options,
            @Nullable Consumer<String> onJobId) {
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
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String dirName,
            String command) {
        return submitTracked(tenantId, projectId, sessionId, processId, dirName, command, SubmitOptions.defaults());
    }

    /** Full-options variant of {@link #submitTracked}. */
    public String submitTracked(
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String dirName,
            String command,
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
    public Optional<Map<String, Object>> renderJob(String tenantId, String projectId, String jobId) {
        return get(tenantId, projectId, jobId).map(j -> ExecJobRenderer.render(j, properties.getInlineOutputCharCap()));
    }

    /** Blocks up to {@code maxMillis} for a RUNNING job to finish. */
    public ExecJob waitFor(ExecJob job, long maxMillis) {
        return jobs.waitFor(job, maxMillis);
    }

    /** Look up a job by tenant + project + id. */
    public Optional<ExecJob> get(String tenantId, String projectId, String jobId) {
        return jobs.get(scopeKey(tenantId, projectId), jobId);
    }

    /** All jobs for a project, insertion-ordered (oldest first). */
    public Collection<ExecJob> listByProject(String tenantId, String projectId) {
        return jobs.list(scopeKey(tenantId, projectId));
    }

    /** Compact status snapshot — no stdout/stderr bodies. */
    public Optional<ExecStat> stat(String tenantId, String projectId, String jobId) {
        return jobs.stat(scopeKey(tenantId, projectId), jobId);
    }

    /**
     * Last {@code n} lines of the requested stream. Reads from the
     * persisted log file so callers see the same content {@code tail -n}
     * would on the host. Returns the lines oldest-first (chronological).
     */
    public List<String> tail(String tenantId, String projectId, String jobId, int n, ExecJobs.Stream stream) {
        return jobs.tail(scopeKey(tenantId, projectId), jobId, n, stream);
    }

    /** Force-kill a still-running job. Returns {@code false} if already terminal. */
    public boolean kill(String tenantId, String projectId, String jobId) {
        return jobs.kill(scopeKey(tenantId, projectId), jobId);
    }

    /**
     * Force-kills every running job for a project — used by
     * {@code ProjectManagerService.archive(...)} once that lands.
     */
    public int killAllForProject(String tenantId, String projectId) {
        return jobs.killAll(scopeKey(tenantId, projectId));
    }

    /**
     * Reconciles jobs stuck in {@code RUNNING} because their worker thread died
     * without running its {@code finally} — see {@link ExecJobs#reconcileOrphans}.
     * Pod-local by design (never cluster-master-gated): the job index and the
     * {@code Process} handles only exist in this JVM. A reconciled job is mirrored
     * to the registry (status → terminal, session no longer pinned, so the
     * IdleSweeper is not blocked) and its owner is notified.
     *
     * @return the number of jobs reconciled to ORPHANED this pass
     */
    public int reconcileOrphanedJobs(Instant now) {
        return jobs.reconcileOrphans(now);
    }

    /** What the engine reports back: registry mirror, completion event, eviction. */
    private final class JobListener implements ExecJobs.Listener {

        @Override
        public void stateChanged(ExecJob job) {
            notifyRegistry(job);
        }

        @Override
        public void finished(ExecJob job) {
            pushCompletionIfTracked(job);
        }

        @Override
        public void evicted(ExecJob job) {
            // Evict the mirrored registry entry too — otherwise the Brain-owned
            // ExecutionRegistryEntry outlives the job and leaks for the pod's
            // lifetime (code-review Phase 2).
            registry.removeById(job.id());
            // Drop any secret values this run pulled via vance.secret(...) so
            // decrypted values don't linger past the job's lifetime.
            ScriptSecretAccumulator.evict(job.labels().get(ExecLabels.KEY_RUN_ID));
        }
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
        ExecCompletionEvent event = new ExecCompletionEvent(
                job.id(),
                job.projectId(),
                job.status().name(),
                job.exitCode(),
                job.startedAt(),
                job.finishedAt(),
                job.killedByWatchdog(),
                ExecJobs.tailFile(job.stdoutFile(), properties.getCompletionTailLines()),
                ExecJobs.tailFile(job.stderrFile(), properties.getCompletionTailLines()));
        try {
            boolean ok = engineMessageRouterProvider
                    .getObject()
                    .dispatch(ownerProcessId, ownerProcessId, event.toMessage(ownerProcessId));
            if (!ok) {
                log.warn("{} dispatch dropped owner='{}' job='{}'", event.eventType(), ownerProcessId, job.id());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "{} dispatch failed owner='{}' job='{}': {}",
                    event.eventType(),
                    ownerProcessId,
                    job.id(),
                    e.toString(),
                    e);
        }
    }

    /** Mirror the job's terminal state into the cross-side registry. */
    private void notifyRegistry(ExecJob job) {
        registry.updateProgress(
                job.id(), job.lastOutputAt(), toRegistryStatus(job.status()), job.exitCode(), job.finishedAt());
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

    private static String scopeKey(String tenantId, String projectId) {
        return tenantId + "/" + projectId;
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
        if (ExecIsolation.enabled(iso.getMode(), iso.getWrapper())) {
            List<String> argv = ExecIsolation.wrap(iso.getWrapper(), cwd.toString(), command);
            log.info("exec isolation: wrapping work_exec command in '{}'", argv.isEmpty() ? "?" : argv.get(0));
            return argv;
        }
        return ExecIsolation.shellArgv(command);
    }

    @PreDestroy
    void shutdown() {
        jobs.close();
    }
}
