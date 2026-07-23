package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaParameterSpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.magrathea.journal.ResultRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Public Magrathea API for starting workflow runs and the single
 * subscriber of {@link TaskCompletedEvent}. Owns every state-machine
 * transition decision; type-executors only compute outcomes (plan
 * §4.0, §6.4).
 *
 * <p>The {@code @EventListener} re-submits its work to the
 * {@link MagratheaProjectLaneManager} so all journal writes and task-queue
 * mutations for a given project happen on a single thread regardless
 * of which thread publishes the event (plan §10).
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class MagratheaWorkflowService {

    /** Counter for fresh workflow starts. Tag: {@code workflow}. */
    /** Max wait for the lane to journal the start-records before start() returns. */
    private static final long START_TIMEOUT_SECONDS = 30;

    private static final String METRIC_STARTS = "vance.magrathea.workflow.starts";

    /** Counter for terminal status writes. Tags: {@code workflow}, {@code status}. */
    private static final String METRIC_TERMINATIONS = "vance.magrathea.workflow.terminations";

    /** Timer for run duration (StartRecord → terminal StatusRecord). Tags: {@code workflow}, {@code status}. */
    private static final String METRIC_DURATION = "vance.magrathea.workflow.duration";

    private final MagratheaWorkflowLoader workflowLoader;
    private final MagratheaJournalService journalService;
    private final MagratheaTaskService taskService;
    private final MagratheaProjectLaneManager laneManager;
    /** Lazy to break the cycle: TaskExecutor → … → WorkflowService → TaskExecutor (on retry paths). */
    private final MagratheaTaskExecutor taskExecutor;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MetricService metricService;

    public MagratheaWorkflowService(
            MagratheaWorkflowLoader workflowLoader,
            MagratheaJournalService journalService,
            MagratheaTaskService taskService,
            MagratheaProjectLaneManager laneManager,
            @Lazy @Autowired MagratheaTaskExecutor taskExecutor,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            MetricService metricService) {
        this.workflowLoader = workflowLoader;
        this.journalService = journalService;
        this.taskService = taskService;
        this.laneManager = laneManager;
        this.taskExecutor = taskExecutor;
        this.eventPublisher = eventPublisher;
        this.metricService = metricService;
    }

    // ──────────── start ────────────

    /**
     * Start a fresh workflow run. Runs the start path on the project
     * lane so the StartRecord, StateEnteredRecord and the first
     * {@code magrathea_tasks} row land atomically with respect to other
     * lane activity.
     *
     * @return the freshly generated {@code workflowRunId}.
     * @throws MagratheaWorkflowException when the workflow YAML cannot be
     *                                 resolved or required params are missing.
     */
    public String start(
            String tenantId,
            String projectId,
            String workflowName,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy) {
        return start(tenantId, projectId, workflowName, callerParams, startedBy,
                /* parentMagratheaProcessId */ null, /* parentState */ null);
    }

    /**
     * Parent-linked overload used by {@code WorkflowTaskExecutor}. The
     * {@code parentMagratheaProcessId} + {@code parentState} pair makes
     * the run discoverable through the journal-projected start record;
     * {@link MagratheaSubWorkflowCompletionListener} uses them to advance
     * the waiting parent task when the sub-run terminates.
     */
    public String start(
            String tenantId,
            String projectId,
            String workflowName,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState) {
        ResolvedMagratheaWorkflow workflow = workflowLoader.load(tenantId, projectId, workflowName)
                .orElseThrow(() -> new MagratheaWorkflowException(
                        "Workflow '" + workflowName + "' not found in cascade for tenant="
                                + tenantId + " project=" + projectId));
        Map<String, Object> resolvedParams = applyDefaultsAndValidate(workflow, callerParams);
        String runId = MagratheaRunIdGenerator.fresh();

        // Enqueue start on the lane and ACTUALLY await it, so the caller
        // sees the runId only after the start-records are journalled — and
        // a write failure propagates instead of leaving the caller with a
        // runId for a run that never materialised (code-review Phase 2).
        try {
            laneManager.submitTracked(projectId, () -> writeStartRecords(
                    tenantId, projectId, runId, workflow, resolvedParams, startedBy,
                    parentMagratheaProcessId, parentState))
                    .get(START_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new MagratheaWorkflowException(
                    "Failed to start workflow '" + workflow.name() + "': " + cause.getMessage(),
                    cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new MagratheaWorkflowException(
                    "Timed out starting workflow '" + workflow.name() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MagratheaWorkflowException(
                    "Interrupted starting workflow '" + workflow.name() + "'", e);
        }

        metricService.counter(METRIC_STARTS, "workflow", workflow.name()).increment();
        return runId;
    }

    private void writeStartRecords(
            String tenantId,
            String projectId,
            String runId,
            ResolvedMagratheaWorkflow workflow,
            Map<String, Object> params,
            @Nullable String startedBy,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState) {

        journalService.append(tenantId, projectId, runId, StartRecord.builder()
                .workflowName(workflow.name())
                .workflowVersion(workflow.version())
                .definitionYaml(workflow.yaml())
                .params(params)
                .startedBy(startedBy)
                .parentMagratheaProcessId(parentMagratheaProcessId)
                .parentState(parentState)
                .build());

        journalService.append(tenantId, projectId, runId,
                StateEnteredRecord.builder().state(workflow.startState()).build());

        MagratheaStateSpec startState = workflow.states().get(workflow.startState());
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .workflowRunId(runId)
                .workflowName(workflow.name())
                .stateName(workflow.startState())
                .taskType(startState.type())
                .status(MagratheaTaskStatus.PENDING)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .attemptCount(0)
                .build();
        taskService.insert(task);
        log.info("Magrathea run {} started workflow='{}' tenant={} project={} startState={}",
                runId, workflow.name(), tenantId, projectId, workflow.startState());
    }

    private Map<String, Object> applyDefaultsAndValidate(
            ResolvedMagratheaWorkflow workflow,
            @Nullable Map<String, Object> callerParams) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> caller = callerParams == null ? Map.of() : callerParams;
        for (Map.Entry<String, MagratheaParameterSpec> e : workflow.parameters().entrySet()) {
            String key = e.getKey();
            MagratheaParameterSpec spec = e.getValue();
            if (caller.containsKey(key)) {
                out.put(key, caller.get(key));
            } else if (spec.defaultValue() != null) {
                out.put(key, spec.defaultValue());
            } else if (spec.required()) {
                throw new MagratheaWorkflowException(
                        "Required parameter '" + key + "' missing for workflow '"
                                + workflow.name() + "'");
            }
        }
        // Caller-supplied parameters not in the schema are passed through
        // — workflows often want to carry context fields that the YAML
        // doesn't bother to declare.
        for (Map.Entry<String, Object> e : caller.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        return Map.copyOf(out);
    }

    // ──────────── completion handling ────────────

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        // Always re-submit to the project lane so journal writes and
        // task-queue mutations stay single-threaded per project, even
        // when async listeners (subprocess termination, inbox-answer,
        // timer fire) publish from foreign threads.
        laneManager.submit(event.projectId(), () -> handleCompletion(event));
    }

    /** Package-private for tests — the real public entry is {@link #onTaskCompleted}. */
    void handleCompletion(TaskCompletedEvent event) {
        Optional<MagratheaTaskDocument> maybe = taskService.findById(event.taskId());
        if (maybe.isEmpty()) {
            log.warn("Magrathea onTaskCompleted: task {} not found — ignoring", event.taskId());
            return;
        }
        MagratheaTaskDocument task = maybe.get();

        // Idempotent TaskResultRecord append. If the row already exists
        // (Mongo unique index on the partial-filter triple), the second
        // append returns empty and we skip the rest — only one event
        // can have effects.
        Optional<MagratheaJournalEntry> appended = journalService.appendIfAbsent(
                event.tenantId(),
                event.projectId(),
                event.workflowRunId(),
                event.taskId(),
                TaskResultRecord.builder()
                        .state(event.stateName())
                        .taskId(event.taskId())
                        .outcome(event.outcome())
                        .output(event.output())
                        .errorMessage(event.errorMessage())
                        .durationMs(event.durationMs())
                        .build());
        if (appended.isEmpty()) {
            log.debug("Magrathea duplicate TaskCompletedEvent for taskId={} dropped", event.taskId());
            return;
        }

        // Re-load fresh definition + state spec for transition resolution.
        Optional<StartRecord> start = journalService.readLast(
                event.workflowRunId(), StartRecord.class);
        if (start.isEmpty()) {
            log.error("Magrathea onTaskCompleted: run {} has no StartRecord", event.workflowRunId());
            markTaskTerminal(task, event.outcome());
            return;
        }

        ResolvedMagratheaWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(start.get().getWorkflowName(),
                    start.get().getDefinitionYaml());
        } catch (RuntimeException ex) {
            log.error("Magrathea onTaskCompleted: cannot re-parse frozen YAML for run {}",
                    event.workflowRunId(), ex);
            markTaskTerminal(task, "failure");
            writeRunFailed(event, "frozen YAML invalid: " + ex.getMessage());
            return;
        }

        MagratheaStateSpec state = workflow.states().get(event.stateName());
        if (state == null) {
            log.error("Magrathea onTaskCompleted: state '{}' not in workflow", event.stateName());
            markTaskTerminal(task, "failure");
            writeRunFailed(event, "state not in workflow: " + event.stateName());
            return;
        }

        // 1. storeAs → VarRecord
        if (state.storeAs() != null && event.output() != null) {
            journalService.append(
                    event.tenantId(), event.projectId(), event.workflowRunId(),
                    VarRecord.builder().key(state.storeAs()).value(event.output()).build());
        }

        // 2. TERMINAL-specific: write StatusRecord + ResultRecord
        if (event.taskType() == MagratheaTaskType.TERMINAL) {
            MagratheaRunStatus runStatus = TaskCompletedEvent.OUTCOME_SUCCESS.equals(event.outcome())
                    ? MagratheaRunStatus.DONE
                    : MagratheaRunStatus.FAILED;
            if (event.output() != null) {
                journalService.append(
                        event.tenantId(), event.projectId(), event.workflowRunId(),
                        ResultRecord.builder().state(event.stateName()).result(event.output()).build());
            }
            journalService.append(
                    event.tenantId(), event.projectId(), event.workflowRunId(),
                    StatusRecord.builder().status(runStatus).reason(event.errorMessage()).build());
            markTaskTerminal(task, event.outcome());

            recordTerminalMetrics(start.get().getWorkflowName(),
                    runStatus, event.workflowRunId());

            // Surface to any parent that's waiting on this run as a sub-workflow.
            publishWorkflowCompleted(event, start.get(), runStatus);
            log.info("Magrathea run {} reached terminal '{}' → {}",
                    event.workflowRunId(), event.stateName(), runStatus);
            return;
        }

        // 3. Retry on matching error-kind before any transition resolution.
        if (canRetry(state, task, event)) {
            markTaskTerminal(task, event.outcome());
            enqueueRetry(event, workflow, state, task.getRetryCount() + 1);
            return;
        }

        // 4. Bounds check before enqueueing any further task — same
        //    place catches both normal transitions and catch-routes.
        Optional<String> boundsViolation = checkBounds(event.workflowRunId(), workflow);
        if (boundsViolation.isPresent()) {
            log.warn("Magrathea run {} bounds exhausted: {}",
                    event.workflowRunId(), boundsViolation.get());
            markTaskTerminal(task, event.outcome());
            writeRunFailed(event, "bounds exhausted: " + boundsViolation.get());
            return;
        }

        // 5. Non-terminal — resolve next state and enqueue
        String nextState = resolveNextState(state, event);
        markTaskTerminal(task, event.outcome());

        if (nextState == null) {
            // No transition matches and no catch matches — treat as run failure.
            log.warn("Magrathea run {} state '{}' produced outcome '{}' with no transition",
                    event.workflowRunId(), event.stateName(), event.outcome());
            writeRunFailed(event, "no transition for outcome '" + event.outcome() + "'");
            return;
        }
        enqueueNextTask(event, workflow, nextState);
    }

    private static boolean canRetry(
            MagratheaStateSpec state, MagratheaTaskDocument task, TaskCompletedEvent event) {
        if (state.retry() == null) return false;
        MagratheaErrorKind kind = parseErrorKind(event.outcome());
        if (kind == null) return false;
        if (!state.retry().onErrorKinds().contains(kind)) return false;
        // maxAttempts counts the original attempt + retries.
        int nextRetryCount = task.getRetryCount() + 1;
        return nextRetryCount < state.retry().maxAttempts();
    }

    private void enqueueRetry(
            TaskCompletedEvent prev,
            ResolvedMagratheaWorkflow workflow,
            MagratheaStateSpec state,
            int retryCount) {
        int backoff = Math.max(0, state.retry().backoffSeconds());
        MagratheaTaskDocument retry = MagratheaTaskDocument.builder()
                .tenantId(prev.tenantId())
                .projectId(prev.projectId())
                .workflowRunId(prev.workflowRunId())
                .workflowName(workflow.name())
                .stateName(prev.stateName())
                .taskType(state.type())
                .status(MagratheaTaskStatus.PENDING)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now().plusSeconds(backoff))
                .attemptCount(0)
                .retryCount(retryCount)
                .build();
        taskService.insert(retry);
        log.info("Magrathea run {} state '{}' retry {} scheduled (backoff={}s)",
                prev.workflowRunId(), prev.stateName(), retryCount, backoff);
    }

    private Optional<String> checkBounds(String workflowRunId, ResolvedMagratheaWorkflow workflow) {
        MagratheaBoundsSpec bounds = workflow.bounds();
        if (bounds == null) return Optional.empty();

        if (bounds.maxWallclockSeconds() != null) {
            Optional<Instant> runStart = journalService.firstCreatedAt(workflowRunId);
            if (runStart.isPresent()) {
                long elapsed = java.time.Duration.between(runStart.get(), Instant.now()).getSeconds();
                if (elapsed > bounds.maxWallclockSeconds()) {
                    return Optional.of("maxWallclockSeconds=" + bounds.maxWallclockSeconds()
                            + " exceeded (elapsed " + elapsed + "s)");
                }
            }
        }
        if (bounds.maxTaskSpawns() != null) {
            long spawned = journalService.count(workflowRunId,
                    de.mhus.vance.shared.magrathea.journal.TaskStartedRecord.class);
            if (spawned > bounds.maxTaskSpawns()) {
                return Optional.of("maxTaskSpawns=" + bounds.maxTaskSpawns()
                        + " exceeded (started " + spawned + ")");
            }
        }
        // maxTotalCostUsd is reserved for the LLM-cost-tracking integration (plan §14).
        return Optional.empty();
    }

    private @Nullable String resolveNextState(MagratheaStateSpec state, TaskCompletedEvent event) {
        if (event.nextStateOverride() != null) {
            return event.nextStateOverride();
        }
        // First the positive `on:` block. Lookups are case-sensitive
        // because the YAML schema doesn't tolerate variants.
        String byOn = state.onOutcomes().get(event.outcome());
        if (byOn != null) return byOn;

        // Then the `catch:` block — outcome interpreted as an error-kind enum name.
        MagratheaErrorKind kind = parseErrorKind(event.outcome());
        if (kind != null) {
            return state.catchKinds().get(kind);
        }
        return null;
    }

    private static @Nullable MagratheaErrorKind parseErrorKind(String outcome) {
        if (outcome == null) return null;
        String norm = outcome.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return MagratheaErrorKind.valueOf(norm);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void enqueueNextTask(
            TaskCompletedEvent prev, ResolvedMagratheaWorkflow workflow, String nextStateName) {
        MagratheaStateSpec next = workflow.states().get(nextStateName);
        if (next == null) {
            log.error("Magrathea run {} transition target '{}' missing from workflow",
                    prev.workflowRunId(), nextStateName);
            writeRunFailed(prev, "transition target missing: " + nextStateName);
            return;
        }
        journalService.append(prev.tenantId(), prev.projectId(), prev.workflowRunId(),
                StateEnteredRecord.builder().state(nextStateName).build());
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .tenantId(prev.tenantId())
                .projectId(prev.projectId())
                .workflowRunId(prev.workflowRunId())
                .workflowName(workflow.name())
                .stateName(nextStateName)
                .taskType(next.type())
                .status(MagratheaTaskStatus.PENDING)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .attemptCount(0)
                .build();
        taskService.insert(task);
    }

    private void markTaskTerminal(MagratheaTaskDocument task, String outcome) {
        if (TaskCompletedEvent.OUTCOME_SUCCESS.equals(outcome)) {
            taskService.markDone(task.getId());
        } else {
            taskService.markFailed(task.getId());
        }
    }

    private void writeRunFailed(TaskCompletedEvent event, String reason) {
        journalService.append(event.tenantId(), event.projectId(), event.workflowRunId(),
                StatusRecord.builder().status(MagratheaRunStatus.FAILED).reason(reason).build());
        String workflowName = journalService.readLast(event.workflowRunId(), StartRecord.class)
                .map(StartRecord::getWorkflowName)
                .orElse("unknown");
        recordTerminalMetrics(workflowName, MagratheaRunStatus.FAILED, event.workflowRunId());
    }

    /**
     * Counter + duration for a terminal status transition. Duration is
     * measured from the StartRecord entry's {@code createdAt} to now —
     * if the StartRecord can't be located (shouldn't happen on healthy
     * paths) the timer is skipped, the counter still fires.
     */
    private void recordTerminalMetrics(
            String workflowName, MagratheaRunStatus status, String workflowRunId) {
        metricService.counter(METRIC_TERMINATIONS,
                "workflow", workflowName,
                "status", status.name()).increment();
        Instant startedAt = findStartInstant(workflowRunId);
        if (startedAt != null) {
            metricService.timer(METRIC_DURATION,
                    "workflow", workflowName,
                    "status", status.name())
                    .record(Duration.between(startedAt, Instant.now()));
        }
    }

    /** Walks the journal in createdAt order; the first entry is the StartRecord. */
    private @Nullable Instant findStartInstant(String workflowRunId) {
        List<MagratheaJournalEntry> entries = journalService.read(workflowRunId);
        if (entries.isEmpty()) return null;
        return entries.get(0).getCreatedAt();
    }

    private void publishWorkflowCompleted(
            TaskCompletedEvent event, StartRecord start, MagratheaRunStatus status) {
        eventPublisher.publishEvent(new WorkflowCompletedEvent(
                event.tenantId(),
                event.projectId(),
                event.workflowRunId(),
                start.getWorkflowName(),
                status,
                event.output(),
                start.getParentMagratheaProcessId(),
                start.getParentState()));
    }

    /** Surfacing-friendly wrapper for invalid start requests. */
    public static class MagratheaWorkflowException extends RuntimeException {
        public MagratheaWorkflowException(String message) {
            super(message);
        }

        public MagratheaWorkflowException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
