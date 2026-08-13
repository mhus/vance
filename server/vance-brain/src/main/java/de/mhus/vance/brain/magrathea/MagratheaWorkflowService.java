package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
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
import java.util.concurrent.TimeUnit;

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
    /** Datenhoheit: document bodies are read through the owning service, never from Mongo directly. */
    private final DocumentService documentService;
    private final MagratheaJournalService journalService;
    private final MagratheaTaskService taskService;
    private final MagratheaProjectLaneManager laneManager;
    /** Lazy to break the cycle: TaskExecutor → … → WorkflowService → TaskExecutor (on retry paths). */
    private final MagratheaTaskExecutor taskExecutor;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MetricService metricService;
    /** Only to stop an agent whose task ended on its deadline. */
    private final ThinkProcessService thinkProcessService;
    /** Unwinding a stopped run: withdraw its gate item, drop its timers. */
    private final de.mhus.vance.shared.inbox.InboxItemService inboxItemService;
    private final de.mhus.vance.shared.magrathea.MagratheaTimerService timerService;

    public MagratheaWorkflowService(
            MagratheaWorkflowLoader workflowLoader,
            DocumentService documentService,
            MagratheaJournalService journalService,
            MagratheaTaskService taskService,
            MagratheaProjectLaneManager laneManager,
            @Lazy @Autowired MagratheaTaskExecutor taskExecutor,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            MetricService metricService,
            ThinkProcessService thinkProcessService,
            de.mhus.vance.shared.inbox.InboxItemService inboxItemService,
            de.mhus.vance.shared.magrathea.MagratheaTimerService timerService) {
        this.workflowLoader = workflowLoader;
        this.documentService = documentService;
        this.journalService = journalService;
        this.taskService = taskService;
        this.laneManager = laneManager;
        this.taskExecutor = taskExecutor;
        this.eventPublisher = eventPublisher;
        this.metricService = metricService;
        this.thinkProcessService = thinkProcessService;
        this.inboxItemService = inboxItemService;
        this.timerService = timerService;
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
        return startResolved(tenantId, projectId, workflow, /* sourcePath */ null,
                callerParams, startedBy, parentMagratheaProcessId, parentState);
    }

    /**
     * Start a run from the document at {@code path} inside
     * {@code projectId}, rather than by resolving a name through the
     * {@code _vance/workflows/} cascade.
     *
     * <p>The location was never an execution requirement: every task
     * re-parses the frozen YAML from the {@code StartRecord}, and the
     * cascade prefix appears nowhere outside the loader. What was missing
     * was a way to say "run *this* document" — which is what a user
     * looking at an open workflow means, and what a scheduler or hook
     * never means. Those keep the name-based route, where the cascade's
     * tenant-override is the point.
     *
     * <p>Same-project only, structurally: the path is resolved inside the
     * caller's project, so no reference can reach another one.
     *
     * @throws MagratheaWorkflowException if no document lives at the path
     * @throws MagratheaWorkflowParseException if its body is not a valid workflow
     */
    public String startFromDocument(
            String tenantId,
            String projectId,
            String path,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy) {
        String norm = path == null ? "" : path.trim();
        if (norm.isEmpty()) {
            throw new MagratheaWorkflowException("Document path is required");
        }
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, norm)
                .orElseThrow(() -> new MagratheaWorkflowException(
                        "No document at '" + norm + "' in project '" + projectId + "'"));

        // Deliberately no `kind: vance-workflow` check: the parser is the
        // real gate, and requiring the header would refuse the legacy
        // definitions under _vance/workflows/ that the name-based route
        // starts happily. The kind drives which documents *offer* the
        // button, not which ones may run.
        ResolvedMagratheaWorkflow workflow = MagratheaWorkflowLoader.parseYaml(
                workflowNameFromPath(norm), documentService.readContent(doc));

        return startResolved(tenantId, projectId, workflow, norm,
                callerParams, startedBy, /* parent */ null, /* parentState */ null);
    }

    /** File stem of {@code path} — what the run is called in listings and metrics. */
    private static String workflowNameFromPath(String path) {
        String stem = path.substring(path.lastIndexOf('/') + 1);
        int dot = stem.lastIndexOf('.');
        return dot > 0 ? stem.substring(0, dot) : stem;
    }

    private String startResolved(
            String tenantId,
            String projectId,
            ResolvedMagratheaWorkflow workflow,
            @Nullable String sourcePath,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState) {
        Map<String, Object> resolvedParams = applyDefaultsAndValidate(workflow, callerParams);
        String runId = MagratheaRunIdGenerator.fresh();

        // Enqueue start on the lane and ACTUALLY await it, so the caller
        // sees the runId only after the start-records are journalled — and
        // a write failure propagates instead of leaving the caller with a
        // runId for a run that never materialised (code-review Phase 2).
        try {
            laneManager.submitTracked(projectId, () -> writeStartRecords(
                    tenantId, projectId, runId, workflow, resolvedParams, startedBy,
                    sourcePath, parentMagratheaProcessId, parentState))
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

    /**
     * Close the ThinkProcess an {@code agent_task} was waiting on when the
     * task ended on its timeout instead.
     *
     * <p>Unlinked first so the resulting {@code CLOSED} event finds no task
     * and stays silent — the outcome is already decided, and a second
     * completion would only be dropped as a duplicate anyway.
     *
     * <p>A timed-out {@code workflow_task} leaves its sub-run going: ending
     * a run from outside needs the stop path that does not exist yet
     * ({@code planning/runs-view.md} §5.4). The parent stops waiting either
     * way; the child finishes into a journal nobody reads.
     */
    private void abandonTimedOutSubProcess(MagratheaTaskDocument task) {
        String subProcessId = task.getSubProcessId();
        if (subProcessId == null || subProcessId.isBlank()) return;
        try {
            taskService.unlinkSubProcess(task.getId());
            thinkProcessService.closeProcess(subProcessId, CloseReason.STOPPED);
            log.info("Magrathea task {} timed out — closed agent process {}",
                    task.getId(), subProcessId);
        } catch (RuntimeException ex) {
            log.warn("Magrathea task {} timed out but agent process {} could not be closed: {}",
                    task.getId(), subProcessId, ex.toString());
        }
    }

    // ──────────── control ────────────

    /**
     * Hold a run: nothing new starts, whatever is in flight finishes.
     *
     * <p>Two parts, and the order is deliberate. The status record goes in
     * first, so a crash between the two leaves a run that says PAUSED with
     * tasks still queued — they run, and the follow-up enqueue then sees
     * the status and holds. The other order would leave a run that says
     * RUNNING with every task held and nobody left to release them.
     *
     * @return {@code true} if this call did the pausing
     */
    public boolean pauseRun(String tenantId, String projectId, String workflowRunId) {
        MagratheaRunStatus status = currentStatus(tenantId, projectId, workflowRunId);
        if (status != MagratheaRunStatus.RUNNING) return false;
        onLane(projectId, () -> {
            journalService.append(tenantId, projectId, workflowRunId,
                    StatusRecord.builder().status(MagratheaRunStatus.PAUSED)
                            .reason("paused from the run view").build());
            long held = taskService.holdRun(workflowRunId);
            log.info("Magrathea run {} paused — {} task(s) held", workflowRunId, held);
        });
        return true;
    }

    /**
     * Release a held run. Tasks go back to the queue first: a crash after
     * that leaves a run that still says PAUSED but makes progress, which
     * heals on the next resume — the reverse would stall it for good.
     */
    public boolean resumeRun(String tenantId, String projectId, String workflowRunId) {
        MagratheaRunStatus status = currentStatus(tenantId, projectId, workflowRunId);
        if (status != MagratheaRunStatus.PAUSED) return false;
        onLane(projectId, () -> {
            long released = taskService.releaseRun(workflowRunId);
            journalService.append(tenantId, projectId, workflowRunId,
                    StatusRecord.builder().status(MagratheaRunStatus.RUNNING)
                            .reason("resumed from the run view").build());
            log.info("Magrathea run {} resumed — {} task(s) released", workflowRunId, released);
        });
        return true;
    }

    /**
     * Stop a run: nothing new starts, everything endable is ended, and the
     * run is marked terminal once nothing is in flight.
     *
     * <p>The classification that matters is not "waiting vs. computing" but
     * <b>deterministically endable vs. opaque</b>. A gate, a timer, a
     * spawned agent and a sub-run can all be ended with one call each —
     * the handles sit right on the task row. Only genuinely executing work
     * (a shell command, a script) has to run out, and while it does the
     * run stands at {@code STOPPING}.
     *
     * <p>A sub-run is stopped recursively; a shell task is left alone.
     *
     * @return {@code true} if this call did the stopping
     */
    public boolean stopRun(String tenantId, String projectId, String workflowRunId, String reason) {
        return endRun(tenantId, projectId, workflowRunId, MagratheaRunStatus.TERMINATED, reason);
    }

    /**
     * The watchdog's terminal path: same unwind as a stop, but the run
     * ends {@code FAILED}. The difference is not cosmetic — a stop is a
     * decision somebody made, a stall is a defect, and a run list that
     * shows the two alike hides the thing worth looking at.
     */
    public boolean failStalledRun(
            String tenantId, String projectId, String workflowRunId, String reason) {
        return endRun(tenantId, projectId, workflowRunId, MagratheaRunStatus.FAILED, reason);
    }

    private boolean endRun(
            String tenantId,
            String projectId,
            String workflowRunId,
            MagratheaRunStatus terminalStatus,
            String reason) {
        MagratheaRunStatus status = currentStatus(tenantId, projectId, workflowRunId);
        if (status == MagratheaRunStatus.DONE
                || status == MagratheaRunStatus.FAILED
                || status == MagratheaRunStatus.TERMINATED) {
            return false;
        }
        onLane(projectId, () -> {
            long held = taskService.holdRun(workflowRunId);
            boolean stillWorking = false;
            int unwound = 0;
            for (MagratheaTaskDocument task : taskService.findByRun(workflowRunId)) {
                if (task.getStatus() != MagratheaTaskStatus.CLAIMED) continue;
                boolean ended = unwind(tenantId, task, reason);
                stillWorking |= !ended;
                if (ended) unwound++;
            }
            timerService.deleteRun(workflowRunId);
            if (!stillWorking) {
                journalService.append(tenantId, projectId, workflowRunId,
                        StatusRecord.builder().status(terminalStatus).reason(reason).build());
                recordTerminalMetrics(workflowNameOf(tenantId, projectId, workflowRunId),
                        terminalStatus, tenantId, projectId, workflowRunId);
            }
            // Says what was ended, not just what was blocked: "0 held" on a
            // run whose only task was already claimed reads like nothing
            // happened, when in fact an agent was just closed.
            log.info("Magrathea run {} → {} ({}) — {} queued task(s) held,"
                            + " {} in-flight task(s) unwound, opaque work remaining: {}",
                    workflowRunId, terminalStatus, reason, held, unwound, stillWorking);
        });
        return true;
    }

    /**
     * End what this task is waiting on.
     *
     * <p>Every branch is individually guarded, and a failure counts the
     * handle as dealt with rather than aborting. This runs inside the
     * stop's lane task, over every claimed task of the run: one throw
     * propagating out — a gate item somebody already answered, a sub-run
     * on an unreachable pod — would leave the run half unwound and
     * <em>not</em> terminal, which is the one outcome a stop must never
     * produce. A handle nobody could release is worth a warning; it is
     * not worth keeping the run alive over.
     *
     * @return {@code true} when the task is finished with, {@code false}
     *         when something opaque is still running and the run has to
     *         wait for it
     */
    private boolean unwind(String tenantId, MagratheaTaskDocument task, String reason) {
        boolean ended = false;
        if (task.getInboxItemId() != null) {
            try {
                inboxItemService.dismiss(tenantId, task.getInboxItemId(), "_magrathea");
            } catch (RuntimeException ex) {
                log.warn("Magrathea stop: could not dismiss gate item '{}': {}",
                        task.getInboxItemId(), ex.toString());
            }
            ended = true;
        }
        if (task.getSubProcessId() != null) {
            taskService.unlinkSubProcess(task.getId());
            try {
                thinkProcessService.closeProcess(task.getSubProcessId(), CloseReason.STOPPED);
            } catch (RuntimeException ex) {
                log.warn("Magrathea stop: could not close agent process '{}': {}",
                        task.getSubProcessId(), ex.toString());
            }
            ended = true;
        }
        if (task.getSubWorkflowRunId() != null) {
            try {
                stopRun(tenantId, task.getProjectId(), task.getSubWorkflowRunId(), reason);
            } catch (RuntimeException ex) {
                log.warn("Magrathea stop: could not stop sub-run '{}': {}",
                        task.getSubWorkflowRunId(), ex.toString());
            }
            ended = true;
        }
        if (ended) {
            taskService.markFailed(task.getId());
            return true;
        }
        // Nothing to pull the plug on — a shell or script task mid-flight.
        return false;
    }

    private String workflowNameOf(String tenantId, String projectId, String workflowRunId) {
        return journalService.readLast(tenantId, projectId, workflowRunId, StartRecord.class)
                .map(StartRecord::getWorkflowName).orElse("unknown");
    }

    /** Projected run status; {@code RUNNING} until a status record says otherwise. */
    public MagratheaRunStatus currentStatus(String tenantId, String projectId, String workflowRunId) {
        return journalService.readLast(tenantId, projectId, workflowRunId, StatusRecord.class)
                .map(StatusRecord::getStatus)
                .orElse(MagratheaRunStatus.RUNNING);
    }

    /** Run on the project lane and wait — same serialisation as every other mutation. */
    private void onLane(String projectId, Runnable work) {
        try {
            laneManager.submitTracked(projectId, work).get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new MagratheaWorkflowException("Run control failed: " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new MagratheaWorkflowException("Run control timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MagratheaWorkflowException("Interrupted during run control", e);
        }
    }

    private void writeStartRecords(
            String tenantId,
            String projectId,
            String runId,
            ResolvedMagratheaWorkflow workflow,
            Map<String, Object> params,
            @Nullable String startedBy,
            @Nullable String sourcePath,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState) {

        journalService.append(tenantId, projectId, runId, StartRecord.builder()
                .workflowName(workflow.name())
                .workflowVersion(workflow.version())
                .definitionYaml(workflow.yaml())
                .params(params)
                .startedBy(startedBy)
                .sourcePath(sourcePath)
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

        // The task is decided now. If it was decided by its deadline rather
        // than by what it waited on, that thing is still running — an agent
        // burning tokens on an answer nobody will read. Stop it.
        if (MagratheaTimeoutScheduler.OUTCOME_TIMEOUT.equals(event.outcome())) {
            abandonTimedOutSubProcess(task);
        }

        // Re-load fresh definition + state spec for transition resolution.
        Optional<StartRecord> start = journalService.readLast(
                event.tenantId(), event.projectId(), event.workflowRunId(), StartRecord.class);
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

            recordTerminalMetrics(start.get().getWorkflowName(), runStatus,
                    event.tenantId(), event.projectId(), event.workflowRunId());

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
        Optional<String> boundsViolation = checkBounds(
                event.tenantId(), event.projectId(), event.workflowRunId(), workflow);
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

    private Optional<String> checkBounds(
            String tenantId, String projectId, String workflowRunId,
            ResolvedMagratheaWorkflow workflow) {
        MagratheaBoundsSpec bounds = workflow.bounds();
        if (bounds == null) return Optional.empty();

        if (bounds.maxWallclockSeconds() != null) {
            Optional<Instant> runStart =
                    journalService.firstCreatedAt(tenantId, projectId, workflowRunId);
            if (runStart.isPresent()) {
                long elapsed = java.time.Duration.between(runStart.get(), Instant.now()).getSeconds();
                if (elapsed > bounds.maxWallclockSeconds()) {
                    return Optional.of("maxWallclockSeconds=" + bounds.maxWallclockSeconds()
                            + " exceeded (elapsed " + elapsed + "s)");
                }
            }
        }
        if (bounds.maxTaskSpawns() != null) {
            long spawned = journalService.count(tenantId, projectId, workflowRunId,
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
        // A task created while the run is paused must not slip past the
        // hold: pausing moved the queue to HELD, but this row is new.
        boolean paused = currentStatus(prev.tenantId(), prev.projectId(), prev.workflowRunId())
                == MagratheaRunStatus.PAUSED;
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .tenantId(prev.tenantId())
                .projectId(prev.projectId())
                .workflowRunId(prev.workflowRunId())
                .workflowName(workflow.name())
                .stateName(nextStateName)
                .taskType(next.type())
                .status(paused ? MagratheaTaskStatus.HELD : MagratheaTaskStatus.PENDING)
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
        Optional<StartRecord> start = journalService.readLast(
                event.tenantId(), event.projectId(), event.workflowRunId(), StartRecord.class);
        String workflowName = start.map(StartRecord::getWorkflowName).orElse("unknown");
        recordTerminalMetrics(workflowName, MagratheaRunStatus.FAILED,
                event.tenantId(), event.projectId(), event.workflowRunId());
        // Surface this non-terminal failure to any parent waiting on the run as a
        // sub-workflow. Only onTaskCompleted's TERMINAL branch publishes the
        // completion event, and there is no WAITING_SUBWORKFLOW recovery scan —
        // so without this a parent workflow_task hangs forever when its sub-run
        // fails via bounds-exhaustion / no-matching-transition / missing target /
        // unparseable frozen YAML. For a top-level run (no parent in the
        // StartRecord) the listener no-ops, so this is safe to fire always.
        start.ifPresent(s -> publishWorkflowCompleted(event, s, MagratheaRunStatus.FAILED));
    }

    /**
     * Counter + duration for a terminal status transition. Duration is
     * measured from the StartRecord entry's {@code createdAt} to now —
     * if the StartRecord can't be located (shouldn't happen on healthy
     * paths) the timer is skipped, the counter still fires.
     */
    private void recordTerminalMetrics(
            String workflowName, MagratheaRunStatus status,
            String tenantId, String projectId, String workflowRunId) {
        metricService.counter(METRIC_TERMINATIONS,
                "workflow", workflowName,
                "status", status.name()).increment();
        Instant startedAt = findStartInstant(tenantId, projectId, workflowRunId);
        if (startedAt != null) {
            metricService.timer(METRIC_DURATION,
                    "workflow", workflowName,
                    "status", status.name())
                    .record(Duration.between(startedAt, Instant.now()));
        }
    }

    /** Walks the journal in createdAt order; the first entry is the StartRecord. */
    private @Nullable Instant findStartInstant(
            String tenantId, String projectId, String workflowRunId) {
        List<MagratheaJournalEntry> entries = journalService.read(tenantId, projectId, workflowRunId);
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
