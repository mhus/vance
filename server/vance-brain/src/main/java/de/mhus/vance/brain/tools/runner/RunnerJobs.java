package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.execution.ExecCompletionEvent;
import de.mhus.vance.brain.execution.ExecutionOwner;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The brain's side of the jobs a runner runs: an entry in the execution registry, so
 * {@code exec_list} and the generic {@code exec_status} / {@code exec_tail} /
 * {@code exec_kill} reach a runner job by its id, and the completion event a process waits
 * for ({@code EXEC_FINISHED} / {@code EXEC_TIMEOUT}).
 *
 * <p>The runner pushes nothing ({@code planning/runner.md} §7): while a job is
 * {@code RUNNING} the brain asks it, with a growing pause (2 s up to 15 s), and stops at
 * the end of the job. That is one cheap {@code exec.stat} per running job — no timer per
 * project, nothing for a runner whose jobs have ended. A runner that stays unreachable
 * for a while is given up on: the job is marked {@code ORPHANED} and the owner told.
 *
 * <p>The watch lives in this pod, like the registry it feeds. After a brain restart the
 * job keeps running on the runner and stays reachable through {@code runner_exec_status},
 * but no event is sent for it.
 */
@Service
@Slf4j
public class RunnerJobs {

    private static final Duration DEFAULT_FIRST_POLL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MAX_POLL = Duration.ofSeconds(15);
    /** Consecutive failed polls (at the longest pause, a few minutes) before the job is given up. */
    private static final int DEFAULT_MAX_FAILURES = 20;
    /** A job nobody finishes watching for this long is dropped from the watch. */
    private static final Duration MAX_WATCH = Duration.ofHours(12);

    private static final int MAX_WATCHES = 1_000;

    private final RunnerWork runner;
    private final ExecutionRegistryService registry;
    private final ObjectProvider<EngineMessageRouter> engineMessageRouter;
    private final ExecProperties properties;

    private final Duration firstPoll;
    private final Duration maxPoll;
    private final int maxFailures;
    private final Map<String, Thread> watches = new ConcurrentHashMap<>();

    @Autowired
    public RunnerJobs(
            RunnerWork runner,
            ExecutionRegistryService registry,
            ObjectProvider<EngineMessageRouter> engineMessageRouter,
            ExecProperties properties) {
        this(
                runner,
                registry,
                engineMessageRouter,
                properties,
                DEFAULT_FIRST_POLL,
                DEFAULT_MAX_POLL,
                DEFAULT_MAX_FAILURES);
    }

    /** With the polling pace spelled out — for tests, which cannot wait seconds per poll. */
    RunnerJobs(
            RunnerWork runner,
            ExecutionRegistryService registry,
            ObjectProvider<EngineMessageRouter> engineMessageRouter,
            ExecProperties properties,
            Duration firstPoll,
            Duration maxPoll,
            int maxFailures) {
        this.runner = runner;
        this.registry = registry;
        this.engineMessageRouter = engineMessageRouter;
        this.properties = properties;
        this.firstPoll = firstPoll;
        this.maxPoll = maxPoll;
        this.maxFailures = maxFailures;
    }

    // ───────────────────── what the router asks ─────────────────────

    /** The job's status with inline output — what {@code exec_status} shows. */
    public Map<String, Object> status(String tenantId, @Nullable String projectId, String jobId) {
        return runner.call(scope(tenantId, projectId), "exec.status", Map.of("id", jobId));
    }

    public Map<String, Object> tail(
            String tenantId, @Nullable String projectId, String jobId, int n, String streamName) {
        return runner.call(
                scope(tenantId, projectId),
                "exec.tail",
                Map.of("id", jobId, "n", n, "stream", "stderr".equalsIgnoreCase(streamName) ? "stderr" : "stdout"));
    }

    /** Kills the job; a kill the user asked for ends the watch without an event, as for the brain's own jobs. */
    public Map<String, Object> kill(String tenantId, @Nullable String projectId, String jobId) {
        Map<String, Object> result = runner.call(scope(tenantId, projectId), "exec.kill", Map.of("id", jobId));
        if (Boolean.TRUE.equals(result.get("killed"))) {
            Thread watch = watches.remove(jobId);
            if (watch != null) {
                watch.interrupt();
            }
            registry.updateProgress(jobId, Instant.now(), ExecutionStatus.KILLED, null, Instant.now());
        }
        return result;
    }

    // ───────────────────── watching a started job ─────────────────────

    /**
     * Enters a job that is still running into the registry and watches it to its end.
     *
     * @param started the runner's answer to {@code exec.start}
     */
    void watch(ToolInvocationContext ctx, Map<String, Object> started, String command, @Nullable String dirName) {
        Object id = started.get("id");
        String projectId = ctx.projectId();
        if (!(id instanceof String jobId) || projectId == null) {
            return;
        }
        String runnerId = runner.runnerId(ctx.tenantId(), projectId).orElse("unknown");
        Instant now = Instant.now();
        registry.register(new ExecutionRegistryEntry(
                jobId,
                new ExecutionOwner.Runner(runnerId),
                ctx.tenantId(),
                projectId,
                ctx.sessionId(),
                ctx.processId(),
                command,
                dirName,
                now,
                now,
                null,
                ExecutionStatus.RUNNING,
                null,
                null,
                null,
                Map.of()));
        if (watches.size() >= MAX_WATCHES) {
            log.warn("Runner job watch limit reached ({}); job '{}' is registered but not watched", MAX_WATCHES, jobId);
            return;
        }
        Thread thread = Thread.ofVirtual().name("runner-job-" + jobId).unstarted(() -> poll(ctx, jobId, now));
        if (watches.putIfAbsent(jobId, thread) == null) {
            thread.start();
        }
    }

    private void poll(ToolInvocationContext ctx, String jobId, Instant startedAt) {
        ToolInvocationContext asked = scope(ctx.tenantId(), ctx.projectId());
        Duration pause = firstPoll;
        int failures = 0;
        Instant giveUp = startedAt.plus(MAX_WATCH);
        try {
            while (Instant.now().isBefore(giveUp)) {
                Thread.sleep(pause);
                pause = pause.multipliedBy(2).compareTo(maxPoll) > 0 ? maxPoll : pause.multipliedBy(2);
                Map<String, Object> stat;
                try {
                    stat = runner.call(asked, "exec.stat", Map.of("id", jobId));
                    failures = 0;
                } catch (ToolException e) {
                    if (++failures >= maxFailures) {
                        log.warn("Runner job '{}' given up after {} failed polls: {}", jobId, failures, e.getMessage());
                        finish(ctx, asked, jobId, startedAt, Map.of("status", "ORPHANED"));
                        return;
                    }
                    continue;
                }
                if (!"RUNNING".equals(stat.get("status"))) {
                    finish(ctx, asked, jobId, startedAt, stat);
                    return;
                }
            }
            log.warn("Runner job '{}' is still running after {}; no longer watched", jobId, MAX_WATCH);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // killed through the router, or the brain is going down
        } finally {
            watches.remove(jobId);
        }
    }

    /** The job ended: the registry learns it, and the owning process gets its event. */
    private void finish(
            ToolInvocationContext ctx,
            ToolInvocationContext asked,
            String jobId,
            Instant startedAt,
            Map<String, Object> stat) {
        String status = String.valueOf(stat.get("status"));
        Integer exitCode = stat.get("exitCode") instanceof Number n ? n.intValue() : null;
        Instant finishedAt = stat.get("finishedAt") instanceof String s ? parse(s) : null;
        boolean timedOut = Boolean.TRUE.equals(stat.get("timedOut"));
        registry.updateProgress(
                jobId, Instant.now(), toStatus(status), exitCode, finishedAt != null ? finishedAt : Instant.now());
        String owner = ctx.processId();
        if (owner == null || owner.isBlank()) {
            return;
        }
        int lines = properties.getCompletionTailLines();
        ExecCompletionEvent event = new ExecCompletionEvent(
                jobId,
                String.valueOf(ctx.projectId()),
                status,
                exitCode,
                startedAt,
                finishedAt,
                timedOut,
                tailOrEmpty(asked, jobId, lines, "stdout"),
                tailOrEmpty(asked, jobId, lines, "stderr"));
        try {
            if (!engineMessageRouter.getObject().dispatch(owner, owner, event.toMessage(owner))) {
                log.warn("{} dispatch dropped owner='{}' job='{}'", event.eventType(), owner, jobId);
            }
        } catch (RuntimeException e) {
            log.warn("{} dispatch failed owner='{}' job='{}': {}", event.eventType(), owner, jobId, e.toString(), e);
        }
    }

    /** The end of one stream; a runner that is gone by now has none to give. */
    private List<String> tailOrEmpty(ToolInvocationContext asked, String jobId, int lines, String stream) {
        try {
            Object result = runner.call(asked, "exec.tail", Map.of("id", jobId, "n", lines, "stream", stream))
                    .get("lines");
            return result instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
        } catch (ToolException e) {
            return List.of();
        }
    }

    private static ExecutionStatus toStatus(String status) {
        return switch (status) {
            case "COMPLETED" -> ExecutionStatus.COMPLETED;
            case "KILLED" -> ExecutionStatus.KILLED;
            case "ORPHANED" -> ExecutionStatus.ORPHANED;
            default -> ExecutionStatus.FAILED;
        };
    }

    private static @Nullable Instant parse(String iso) {
        try {
            return Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** The context a runner call about a project's jobs runs in — no process, the project is the scope. */
    private static ToolInvocationContext scope(String tenantId, @Nullable String projectId) {
        return new ToolInvocationContext(tenantId, projectId, null, null, null);
    }

    @PreDestroy
    void shutdown() {
        watches.values().forEach(Thread::interrupt);
    }
}
