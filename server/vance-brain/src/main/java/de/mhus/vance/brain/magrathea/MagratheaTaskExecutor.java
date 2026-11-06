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
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
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
    private final org.springframework.beans.factory.ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter>
            progressEmitterProvider;

    private final org.springframework.beans.factory.ObjectProvider<
                    de.mhus.vance.shared.thinkprocess.ThinkProcessService>
            thinkProcessServiceProvider;

    private final MagratheaProperties properties;

    /** Calls to outside services run here, off the project lane — one cheap virtual thread each. */
    private final java.util.concurrent.ExecutorService callPool =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** Places for in-flight calls, per project — see {@link MagratheaProperties#getMaxConcurrentCalls()}. */
    private final Map<String, java.util.concurrent.Semaphore> callPlaces =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The calls running off the lane on this pod, by task id — what a stop or an abort can reach. */
    private final Map<String, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    /** One call running off the lane. */
    private static final class InFlight {
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        volatile java.util.concurrent.@org.jspecify.annotations.Nullable Future<?> future;
        volatile @org.jspecify.annotations.Nullable MagratheaTypeExecutor executor;
        volatile @org.jspecify.annotations.Nullable MagratheaTaskContext context;
    }

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
            org.springframework.beans.factory.ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter>
                    progressEmitterProvider,
            org.springframework.beans.factory.ObjectProvider<de.mhus.vance.shared.thinkprocess.ThinkProcessService>
                    thinkProcessServiceProvider,
            MagratheaProperties properties) {
        this.properties = properties;
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
                throw new IllegalStateException("Duplicate MagratheaTypeExecutor for " + executor.type()
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
    public @org.jspecify.annotations.Nullable MagratheaTypeExecutor executorFor(MagratheaTaskType type) {
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
            log.error(
                    "Magrathea task {} references run {} without StartRecord — failing",
                    task.getId(),
                    task.getWorkflowRunId());
            publishFailure(task, "missing StartRecord");
            return;
        }
        StartRecord start = maybeStart.get();

        ResolvedMagratheaWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(start.getWorkflowName(), start.getDefinitionYaml());
        } catch (RuntimeException ex) {
            log.error(
                    "Magrathea task {} cannot re-parse frozen YAML for run {}",
                    task.getId(),
                    task.getWorkflowRunId(),
                    ex);
            publishFailure(task, "frozen YAML invalid: " + ex.getMessage());
            return;
        }

        MagratheaStateSpec state = workflow.states().get(task.getStateName());
        if (state == null) {
            log.error(
                    "Magrathea task {} references state '{}' which is not in the frozen workflow",
                    task.getId(),
                    task.getStateName());
            publishFailure(task, "state not in workflow: " + task.getStateName());
            return;
        }

        MagratheaTypeExecutor executor = byType.get(state.type());
        if (executor == null) {
            log.error("Magrathea task {} has type {} but no executor is registered", task.getId(), state.type());
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
            log.info(
                    "Magrathea run {} state '{}' cannot run with this binding: {}",
                    task.getWorkflowRunId(),
                    task.getStateName(),
                    lacking);
            publishOutcome(task, state.type(), TaskOutcome.capabilityMissing(lacking), 0L);
            return;
        }

        // Replay vars + params for the executor's view.
        Map<String, Object> vars =
                projector.projectVars(task.getTenantId(), task.getProjectId(), task.getWorkflowRunId());
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
        MagratheaStateSpec resolvedState =
                state.withSpec(new MagratheaSubstitutor(params, vars).substituteSpec(state.spec(), typedKeysOf(state)));

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
                start.getOwnerProcessId(),
                state.type() == MagratheaTaskType.FOR_EACH_TASK ? previousStateOf(task) : null);

        long started = System.currentTimeMillis();
        Optional<TaskOutcome> outcome;
        // Heartbeat while a synchronous task runs: without it a task that
        // takes longer than the reclaim grace (runStatus stays null for
        // sync executors) would be reclaimed and double-executed. A crash
        // stops the heartbeat, so a genuinely dead task still goes stale
        // and is reclaimed (code-review Phase 2 HIGH #3).
        String taskId = task.getId();
        java.util.concurrent.ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        taskService.touchHeartbeat(taskId);
                    } catch (RuntimeException hbEx) {
                        log.trace("heartbeat touch failed for task {}: {}", taskId, hbEx.toString());
                    }
                },
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS);
        if (executor.runsOffLane()) {
            // The lane is free again as soon as this returns; the heartbeat keeps the claim alive
            // meanwhile, and a pod that dies stops it, so the task goes stale and is reclaimed as before.
            startOffLane(task, state.type(), executor, ctx, heartbeat, started);
            return;
        }
        try {
            outcome = executor.execute(ctx);
        } catch (RuntimeException ex) {
            log.error(
                    "Magrathea type-executor {} threw on task {}",
                    executor.getClass().getSimpleName(),
                    task.getId(),
                    ex);
            publishFailure(task, ex.getMessage());
            return;
        } finally {
            heartbeat.cancel(false);
        }

        if (outcome.isEmpty()) {
            log.debug("Magrathea task {} ({}) is async — waiting for listener", task.getId(), state.type());
            return;
        }
        publishOutcome(task, state.type(), outcome.get(), System.currentTimeMillis() - started);
    }

    /**
     * Hand a task that waits on an outside service to its own thread. The result comes back through the
     * completion event, which re-enters the project lane for the journal writes and the transition — only the
     * waiting is off it.
     */
    private void startOffLane(
            MagratheaTaskDocument task,
            MagratheaTaskType type,
            MagratheaTypeExecutor executor,
            MagratheaTaskContext ctx,
            java.util.concurrent.ScheduledFuture<?> heartbeat,
            long started) {
        InFlight flight = new InFlight();
        flight.executor = executor;
        flight.context = ctx;
        inFlight.put(task.getId(), flight);
        flight.future = callPool.submit(() -> runOffLane(task, type, executor, ctx, heartbeat, flight, started));
    }

    private void runOffLane(
            MagratheaTaskDocument task,
            MagratheaTaskType type,
            MagratheaTypeExecutor executor,
            MagratheaTaskContext ctx,
            java.util.concurrent.ScheduledFuture<?> heartbeat,
            InFlight flight,
            long started) {
        Optional<TaskOutcome> outcome;
        java.util.concurrent.Semaphore place = placesFor(task.getProjectId(), type);
        boolean acquired = false;
        try {
            if (place != null) {
                place.acquire();
                acquired = true;
            }
            if (flight.cancelled.get()) return;
            outcome = executor.execute(ctx);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!flight.cancelled.get()) publishFailure(task, "interrupted while waiting for a place to call");
            return;
        } catch (RuntimeException ex) {
            // A cancelled call fails by being interrupted; its stop or abort already decided the task.
            if (!flight.cancelled.get()) {
                log.error(
                        "Magrathea type-executor {} threw on task {}",
                        executor.getClass().getSimpleName(),
                        task.getId(),
                        ex);
                publishFailure(task, ex.getMessage());
            }
            return;
        } finally {
            if (acquired) place.release();
            heartbeat.cancel(false);
            inFlight.remove(task.getId());
        }
        if (flight.cancelled.get() || outcome.isEmpty()) return;
        publishOutcome(task, type, outcome.get(), System.currentTimeMillis() - started);
    }

    /** The places for this project's calls, or {@code null} when the limit is off. */
    private java.util.concurrent.@org.jspecify.annotations.Nullable Semaphore placesFor(
            String projectId, MagratheaTaskType type) {
        int max = properties.getMaxConcurrentCalls();
        if (max <= 0) return null;
        // Per project and per kind: five long shell commands must not keep a tool call from starting.
        return callPlaces.computeIfAbsent(
                projectId + "/" + type.name(), id -> new java.util.concurrent.Semaphore(max, true));
    }

    /** Whether a call of this task is running off the lane on this pod. */
    public boolean isInFlight(String taskId) {
        return inFlight.containsKey(taskId);
    }

    /**
     * End a call that runs off the lane on this pod: it is interrupted, and what it returns afterwards is
     * dropped — whoever ended it (a stop, an abort) has decided the task. A call on another pod, or one that
     * is not running, is not reachable and answers {@code false}.
     */
    public boolean cancelInFlight(String taskId) {
        InFlight flight = inFlight.get(taskId);
        if (flight == null) return false;
        flight.cancelled.set(true);
        // What the call started outside its thread — a shell command on the host — is not ended by an interrupt,
        // and it is ended first: the thread that waits for it forgets the job when it unwinds, so interrupting
        // it before this would leave nothing to find and the command running.
        MagratheaTypeExecutor executor = flight.executor;
        MagratheaTaskContext context = flight.context;
        if (executor != null && context != null) {
            try {
                executor.cancelRunning(context);
            } catch (RuntimeException ex) {
                log.warn("Magrathea task {}: could not end what the call started: {}", taskId, ex.toString());
            }
        }
        java.util.concurrent.Future<?> future = flight.future;
        if (future != null) future.cancel(true);
        return true;
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
        java.util.Set<String> have = start.getCapabilities() == null ? java.util.Set.of() : start.getCapabilities();
        for (de.mhus.vance.api.magrathea.RunCapability capability : needed) {
            if (!have.contains(capability.name())) {
                return "state '" + state.name() + "' needs " + capability;
            }
        }
        return null;
    }

    /**
     * The spec keys whose placeholders keep their type. A tool and a sub-workflow take
     * structured input (arguments, parameters, the items of a for-each), so a value that is
     * one placeholder stays a list or an object there; every other state reads text.
     */
    private static java.util.Set<String> typedKeysOf(MagratheaStateSpec state) {
        return switch (state.type()) {
            case TOOL_TASK -> java.util.Set.of("params");
            case WORKFLOW_TASK -> java.util.Set.of("params", "items");
            // The list stays a list, and what an element passes on keeps its type.
            case FOR_EACH_TASK -> java.util.Set.of("items", "collect");
            default -> java.util.Set.of();
        };
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
    private void emitProgress(@org.jspecify.annotations.Nullable String ownerProcessId, MagratheaStateSpec state) {
        if (ownerProcessId == null || ownerProcessId.isBlank()) return;
        if (state.type() == MagratheaTaskType.CONDITION_TASK
                || state.type() == MagratheaTaskType.FOR_EACH_TASK
                || state.type() == MagratheaTaskType.TERMINAL) {
            return;
        }
        de.mhus.vance.brain.progress.ProgressEmitter emitter = progressEmitterProvider.getIfAvailable();
        var processService = thinkProcessServiceProvider.getIfAvailable();
        if (emitter == null || processService == null) return;
        try {
            processService
                    .findById(ownerProcessId)
                    .ifPresent(owner ->
                            emitter.emitStatus(owner, de.mhus.vance.api.progress.StatusTag.DELEGATING, state.name()));
        } catch (RuntimeException ex) {
            log.trace("Magrathea progress emit failed for owner {}: {}", ownerProcessId, ex.toString());
        }
    }

    /**
     * The state the run was in before the one this task belongs to: the one before the last
     * {@code StateEnteredRecord}, which is this task's own state.
     */
    private @org.jspecify.annotations.Nullable String previousStateOf(MagratheaTaskDocument task) {
        java.util.List<de.mhus.vance.shared.magrathea.journal.StateEnteredRecord> entered = journalService.readAll(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                de.mhus.vance.shared.magrathea.journal.StateEnteredRecord.class);
        return entered.size() >= 2 ? entered.get(entered.size() - 2).getState() : null;
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
            MagratheaTaskDocument task, MagratheaTaskType stateType, TaskOutcome outcome, long durationMs) {
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
                outcome.nextStateOverride(),
                false,
                outcome.vars()));
    }
}
