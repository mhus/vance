package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Type-dispatcher. Indexed by {@link MagratheaTaskType}, it looks up the
 * matching {@link MagratheaTypeExecutor} bean, writes the
 * {@code TaskStartedRecord}, runs the executor, and (for synchronous
 * executors) publishes the {@link TaskCompletedEvent} so the
 * {@code MagratheaWorkflowService} listener can flush the journal and
 * enqueue the next task (plan §4.0).
 *
 * <p>Always invoked from a {@link MagratheaProjectLane} — never directly
 * from a Spring scheduler or a web thread.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaTaskExecutor {

    /**
     * Heartbeat cadence for a running synchronous task — well under the
     * reclaim grace (5 min) so a live long task stays claimed, but a
     * crashed pod's task goes stale within the grace and is reclaimed
     * (code-review Phase 2).
     */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60;

    private final MagratheaJournalService journalService;
    private final MagratheaStateProjector projector;
    private final MagratheaWorkflowLoader workflowLoader;
    private final MagratheaCompletionEventBus eventBus;
    private final de.mhus.vance.shared.magrathea.MagratheaTaskService taskService;
    private final Map<MagratheaTaskType, MagratheaTypeExecutor> byType;
    /** Optional: only a run owned by a process has anyone to report progress to. */
    private final org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.brain.progress.ProgressEmitter> progressEmitterProvider;
    private final org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.shared.thinkprocess.ThinkProcessService> thinkProcessServiceProvider;

    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "magrathea-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public MagratheaTaskExecutor(
            MagratheaJournalService journalService,
            MagratheaStateProjector projector,
            MagratheaWorkflowLoader workflowLoader,
            MagratheaCompletionEventBus eventBus,
            de.mhus.vance.shared.magrathea.MagratheaTaskService taskService,
            List<MagratheaTypeExecutor> executors,
            org.springframework.beans.factory.ObjectProvider<
                    de.mhus.vance.brain.progress.ProgressEmitter> progressEmitterProvider,
            org.springframework.beans.factory.ObjectProvider<
                    de.mhus.vance.shared.thinkprocess.ThinkProcessService> thinkProcessServiceProvider) {
        this.journalService = journalService;
        this.projector = projector;
        this.workflowLoader = workflowLoader;
        this.eventBus = eventBus;
        this.taskService = taskService;
        this.progressEmitterProvider = progressEmitterProvider;
        this.thinkProcessServiceProvider = thinkProcessServiceProvider;
        this.byType = new EnumMap<>(MagratheaTaskType.class);
        for (MagratheaTypeExecutor executor : executors) {
            MagratheaTypeExecutor previous = byType.put(executor.type(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate MagratheaTypeExecutor for " + executor.type()
                                + ": " + previous.getClass().getName() + " vs "
                                + executor.getClass().getName());
            }
        }
    }

    /**
     * The executor registered for {@code type}, or null if none is — which
     * the workflow loader already rejects, so callers may treat null as
     * "nothing to say about this type".
     *
     * <p>Exposed so the start path can ask each state's executor what the
     * run must be bound to, without duplicating the type→bean index.
     */
    public @org.jspecify.annotations.Nullable MagratheaTypeExecutor executorFor(
            MagratheaTaskType type) {
        return byType.get(type);
    }

    /**
     * Run a single claimed task. Caller is the project lane; the lane
     * guarantees no concurrent invocations for the same project.
     */
    public void execute(MagratheaTaskDocument task) {
        Optional<StartRecord> maybeStart = journalService.readLast(
                task.getTenantId(), task.getProjectId(), task.getWorkflowRunId(), StartRecord.class);
        if (maybeStart.isEmpty()) {
            log.error("Magrathea task {} references run {} without StartRecord — failing",
                    task.getId(), task.getWorkflowRunId());
            publishFailure(task, "missing StartRecord");
            return;
        }
        StartRecord start = maybeStart.get();

        ResolvedMagratheaWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(start.getWorkflowName(), start.getDefinitionYaml());
        } catch (RuntimeException ex) {
            log.error("Magrathea task {} cannot re-parse frozen YAML for run {}",
                    task.getId(), task.getWorkflowRunId(), ex);
            publishFailure(task, "frozen YAML invalid: " + ex.getMessage());
            return;
        }

        MagratheaStateSpec state = workflow.states().get(task.getStateName());
        if (state == null) {
            log.error("Magrathea task {} references state '{}' which is not in the frozen workflow",
                    task.getId(), task.getStateName());
            publishFailure(task, "state not in workflow: " + task.getStateName());
            return;
        }

        MagratheaTypeExecutor executor = byType.get(state.type());
        if (executor == null) {
            log.error("Magrathea task {} has type {} but no executor is registered",
                    task.getId(), state.type());
            publishFailure(task, "no executor for type " + state.type());
            return;
        }

        // The start refused every state whose capability this run lacks —
        // every state except the ones that catch `capability_missing`, which
        // is the author saying "let it run and hand me the failure". This is
        // where that failure is produced. Without it the waiver was a way to
        // switch the start check off and nothing else: the exempted state ran
        // as though the capability were there (an agent_task with
        // `inheritContext:` quietly inheriting nothing) and the catch: branch
        // was unreachable, so the symptom was answers missing context nobody
        // could see was dropped.
        String lacking = missingCapability(executor, state, start);
        if (lacking != null) {
            log.info("Magrathea run {} state '{}' cannot run with this binding: {}",
                    task.getWorkflowRunId(), task.getStateName(), lacking);
            publishOutcome(task, state.type(), TaskOutcome.capabilityMissing(lacking), 0L);
            return;
        }

        // Replay vars + params for the executor's view.
        Map<String, Object> vars = projector.projectVars(
                task.getTenantId(), task.getProjectId(), task.getWorkflowRunId());
        Map<String, Object> params = start.getParams() == null ? Map.of() : start.getParams();

        // TaskStartedRecord — marks the execution window opened.
        journalService.append(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                TaskStartedRecord.builder()
                        .state(task.getStateName())
                        .taskType(state.type())
                        .taskId(task.getId())
                        .claimedBy(task.getClaimedBy())
                        .subProcessId(task.getSubProcessId())
                        .subWorkflowRunId(task.getSubWorkflowRunId())
                        .build());

        emitProgress(start.getOwnerProcessId(), state);

        // Resolve ${params.X}/${state.X} in the spec once, so every
        // type-executor sees concrete values instead of raw placeholders —
        // this is the task-to-task dataflow that was documented but never
        // implemented (code-review Phase 2). SpEL fields (no ${}) pass
        // through untouched.
        MagratheaStateSpec resolvedState = state.withSpec(
                new MagratheaSubstitutor(params, vars).substituteSpec(state.spec()));

        MagratheaTaskContext ctx = new MagratheaTaskContext(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                start.getStartedBy(),
                workflow,
                resolvedState,
                params,
                vars,
                start.getSessionId(),
                start.getOwnerProcessId());

        long started = System.currentTimeMillis();
        Optional<TaskOutcome> outcome;
        // Heartbeat while a synchronous task runs: without it a task that
        // takes longer than the reclaim grace (runStatus stays null for
        // sync executors) would be reclaimed and double-executed. A crash
        // stops the heartbeat, so a genuinely dead task still goes stale
        // and is reclaimed (code-review Phase 2 HIGH #3).
        String taskId = task.getId();
        java.util.concurrent.ScheduledFuture<?> heartbeat =
                heartbeatScheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                taskService.touchHeartbeat(taskId);
                            } catch (RuntimeException hbEx) {
                                log.trace("heartbeat touch failed for task {}: {}",
                                        taskId, hbEx.toString());
                            }
                        },
                        HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS);
        try {
            outcome = executor.execute(ctx);
        } catch (RuntimeException ex) {
            log.error("Magrathea type-executor {} threw on task {}",
                    executor.getClass().getSimpleName(), task.getId(), ex);
            publishFailure(task, ex.getMessage());
            return;
        } finally {
            heartbeat.cancel(false);
        }

        if (outcome.isEmpty()) {
            log.debug("Magrathea task {} ({}) is async — waiting for listener",
                    task.getId(), state.type());
            return;
        }
        publishOutcome(task, state.type(), outcome.get(), System.currentTimeMillis() - started);
    }

    /**
     * The first capability this state needs and the run does not have, or
     * {@code null} when it can run.
     *
     * <p>Read from the frozen {@link StartRecord} rather than recomputed:
     * the run's binding must not change under a running plan, which is the
     * same reason the record carries it in the first place.
     */
    private static @org.jspecify.annotations.Nullable String missingCapability(
            MagratheaTypeExecutor executor, MagratheaStateSpec state, StartRecord start) {
        java.util.Set<de.mhus.vance.api.magrathea.RunCapability> needed = executor.requires(state);
        if (needed.isEmpty()) return null;
        java.util.Set<String> have = start.getCapabilities() == null
                ? java.util.Set.of()
                : start.getCapabilities();
        for (de.mhus.vance.api.magrathea.RunCapability capability : needed) {
            if (!have.contains(capability.name())) {
                return "state '" + state.name() + "' needs " + capability;
            }
        }
        return null;
    }

    /**
     * Show the owning process what its run is doing now, so a person
     * watching a conversation sees a plan move instead of a silent wait.
     *
     * <p>Only for steps that can take time. {@code condition_task} and
     * {@code terminal} are control flow — one is over in microseconds, the
     * other is the end, which the owner learns from
     * {@link MagratheaOwnerNotifier} anyway. Announcing them would turn a
     * twenty-state plan into twenty lines of noise, and the rule "does this
     * step do work" is one that does not drift as types are added.
     *
     * <p>Headless runs emit nothing: there is nobody to tell.
     */
    private void emitProgress(
            @org.jspecify.annotations.Nullable String ownerProcessId, MagratheaStateSpec state) {
        if (ownerProcessId == null || ownerProcessId.isBlank()) return;
        if (state.type() == MagratheaTaskType.CONDITION_TASK
                || state.type() == MagratheaTaskType.TERMINAL) {
            return;
        }
        de.mhus.vance.brain.progress.ProgressEmitter emitter = progressEmitterProvider.getIfAvailable();
        var processService = thinkProcessServiceProvider.getIfAvailable();
        if (emitter == null || processService == null) return;
        try {
            processService.findById(ownerProcessId).ifPresent(owner ->
                    emitter.emitStatus(owner,
                            de.mhus.vance.api.progress.StatusTag.DELEGATING,
                            state.name()));
        } catch (RuntimeException ex) {
            log.trace("Magrathea progress emit failed for owner {}: {}",
                    ownerProcessId, ex.toString());
        }
    }

    private void publishFailure(MagratheaTaskDocument task, String message) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                task.getTaskType(),
                "technical_error",
                null,
                message,
                0L,
                null));
    }

    private void publishOutcome(
            MagratheaTaskDocument task,
            MagratheaTaskType stateType,
            TaskOutcome outcome,
            long durationMs) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                stateType,
                outcome.outcome(),
                outcome.output(),
                outcome.errorMessage(),
                durationMs,
                outcome.nextStateOverride()));
    }

}
