package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.RunCapability;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaDebugSetup;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaParameterSpec;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.magrathea.journal.BreakpointsRecord;
import de.mhus.vance.shared.magrathea.journal.DebugRecord;
import de.mhus.vance.shared.magrathea.journal.ResultRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

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
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@Slf4j
public class MagratheaWorkflowService {

    /** Counter for fresh workflow starts. Tag: {@code workflow}. */
    /** Max wait for the lane to journal the start-records before start() returns. */
    private static final long START_TIMEOUT_SECONDS = 30;

    /** Bound on the walk up through parent runs, against a corrupt cycle. */
    private static final int MAX_ANCESTOR_HOPS = 16;

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
    /** Hands each freshly inserted task to this pod's lane instead of waiting for the claimer. */
    private final MagratheaLocalDispatch localDispatch;
    /** Reads the current variables when a counter has to be advanced. */
    private final de.mhus.vance.shared.magrathea.MagratheaStateProjector stateProjector;
    /** Tells an owning process that its run is waiting, or done. */
    private final MagratheaOwnerNotifier ownerNotifier;

    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MetricService metricService;
    /** Only to stop an agent whose task ended on its deadline. */
    private final ThinkProcessService thinkProcessService;
    /** Unwinding a stopped run: withdraw its gate item, drop its timers. */
    private final de.mhus.vance.shared.inbox.MaximegalonService inboxItemService;

    private final de.mhus.vance.shared.magrathea.MagratheaTimerService timerService;

    public MagratheaWorkflowService(
            MagratheaWorkflowLoader workflowLoader,
            DocumentService documentService,
            MagratheaJournalService journalService,
            MagratheaTaskService taskService,
            MagratheaProjectLaneManager laneManager,
            @Lazy @Autowired MagratheaTaskExecutor taskExecutor,
            MagratheaLocalDispatch localDispatch,
            de.mhus.vance.shared.magrathea.MagratheaStateProjector stateProjector,
            MagratheaOwnerNotifier ownerNotifier,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            MetricService metricService,
            ThinkProcessService thinkProcessService,
            de.mhus.vance.shared.inbox.MaximegalonService inboxItemService,
            de.mhus.vance.shared.magrathea.MagratheaTimerService timerService) {
        this.workflowLoader = workflowLoader;
        this.documentService = documentService;
        this.journalService = journalService;
        this.taskService = taskService;
        this.laneManager = laneManager;
        this.taskExecutor = taskExecutor;
        this.localDispatch = localDispatch;
        this.stateProjector = stateProjector;
        this.ownerNotifier = ownerNotifier;
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
        return start(
                tenantId,
                projectId,
                workflowName,
                callerParams,
                startedBy,
                /* parentMagratheaProcessId */ null, /* parentState */
                null);
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
        return start(
                tenantId,
                projectId,
                workflowName,
                callerParams,
                startedBy,
                parentMagratheaProcessId,
                parentState,
                MagratheaRunBinding.headless());
    }

    /**
     * Bound start — the run belongs to a session and/or to a ThinkProcess
     * that waits for its result. This is the Vogon path; every other
     * trigger (scheduler, event, hook, tool, REST) starts headless, and
     * says so by not calling this overload.
     */
    public String start(
            String tenantId,
            String projectId,
            String workflowName,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState,
            MagratheaRunBinding binding) {
        if (parentMagratheaProcessId == null) {
            return start(
                    tenantId,
                    projectId,
                    workflowName,
                    callerParams,
                    startedBy,
                    null,
                    parentState,
                    binding,
                    MagratheaDebugSetup.OFF);
        }
        // A sub-run of a debuggable run is debugged along with it.
        ResolvedMagratheaWorkflow workflow = workflowLoader
                .load(tenantId, projectId, workflowName)
                .orElseThrow(() -> new MagratheaWorkflowException("Workflow '" + workflowName
                        + "' not found in cascade for tenant=" + tenantId + " project=" + projectId));
        return startResolved(
                tenantId,
                projectId,
                workflow, /* sourcePath */
                null,
                callerParams,
                startedBy,
                parentMagratheaProcessId,
                parentState,
                binding,
                inheritedDebug(tenantId, projectId, parentMagratheaProcessId, workflow));
    }

    /**
     * The debug setup a sub-run gets from the run that starts it.
     *
     * <p>A breakpoint belongs to the workflow, not to one run of it, so each
     * element of a for-each halts at the same state: the sub-run takes the
     * parent's entry for its workflow as its breakpoints (names the workflow
     * has no state for are dropped — the definition may have changed since they
     * were set), and hands the whole map on for runs deeper down. It steps if
     * the parent is stepping <em>now</em>, so a resume on the parent ends the
     * per-element halts and leaves only the breakpoints. A parent that is not
     * debuggable leaves the sub-run an ordinary run.
     */
    private MagratheaDebugSetup inheritedDebug(
            String tenantId, String projectId, String parentRunId, ResolvedMagratheaWorkflow workflow) {
        Optional<StartRecord> parent = journalService.readLast(tenantId, projectId, parentRunId, StartRecord.class);
        if (parent.isEmpty() || !MagratheaDebugSetup.isDebuggable(parent.get())) return MagratheaDebugSetup.OFF;
        Map<String, Set<String>> byWorkflow = currentSubBreakpoints(tenantId, projectId, parentRunId, parent.get());
        Set<String> own = new java.util.LinkedHashSet<>();
        for (String name : byWorkflow.getOrDefault(workflow.name(), Set.of())) {
            if (workflow.states().containsKey(name)) own.add(name);
        }
        boolean stepping = isStepping(tenantId, projectId, parentRunId, parent.get());
        return new MagratheaDebugSetup(stepping, own, byWorkflow);
    }

    /**
     * Start with a debug setup: the run halts before every state ({@code
     * stepping}), before named states ({@code breakpoints}), or both, and waits
     * to be stepped from the run view (see {@link #stepRun}). The calls are real
     * — debug is not a dry run.
     */
    public String start(
            String tenantId,
            String projectId,
            String workflowName,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            @Nullable String parentMagratheaProcessId,
            @Nullable String parentState,
            MagratheaRunBinding binding,
            MagratheaDebugSetup debug) {
        ResolvedMagratheaWorkflow workflow = workflowLoader
                .load(tenantId, projectId, workflowName)
                .orElseThrow(() -> new MagratheaWorkflowException("Workflow '" + workflowName
                        + "' not found in cascade for tenant=" + tenantId + " project=" + projectId));
        return startResolved(
                tenantId,
                projectId,
                workflow, /* sourcePath */
                null,
                callerParams,
                startedBy,
                parentMagratheaProcessId,
                parentState,
                binding,
                debug);
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
        return startFromDocument(tenantId, projectId, path, callerParams, startedBy, MagratheaRunBinding.headless());
    }

    /**
     * Bound variant of {@link #startFromDocument} — the run belongs to the
     * session and process in {@code binding}.
     *
     * <p>Exists because addressing a plan by path and running it for
     * somebody are independent choices: Vogon does both, and without this
     * overload a path-addressed plan would silently lose its owner and end
     * up unable to report back.
     */
    public String startFromDocument(
            String tenantId,
            String projectId,
            String path,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            MagratheaRunBinding binding) {
        return startFromDocument(tenantId, projectId, path, callerParams, startedBy, binding, MagratheaDebugSetup.OFF);
    }

    /** {@link #startFromDocument} with a debug setup — see {@link #start}. */
    public String startFromDocument(
            String tenantId,
            String projectId,
            String path,
            @Nullable Map<String, Object> callerParams,
            @Nullable String startedBy,
            MagratheaRunBinding binding,
            MagratheaDebugSetup debug) {
        String norm = path == null ? "" : path.trim();
        if (norm.isEmpty()) {
            throw new MagratheaWorkflowException("Document path is required");
        }
        DocumentDocument doc = documentService
                .findByPath(tenantId, projectId, norm)
                .orElseThrow(() ->
                        new MagratheaWorkflowException("No document at '" + norm + "' in project '" + projectId + "'"));

        // Deliberately no `kind: vance-workflow` check: the parser is the
        // real gate, and requiring the header would refuse the legacy
        // definitions under _vance/workflows/ that the name-based route
        // starts happily. The kind drives which documents *offer* the
        // button, not which ones may run.
        ResolvedMagratheaWorkflow workflow =
                MagratheaWorkflowLoader.parseYaml(workflowNameFromPath(norm), documentService.readContent(doc));

        return startResolved(
                tenantId,
                projectId,
                workflow,
                norm,
                callerParams,
                startedBy, /* parent */
                null, /* parentState */
                null,
                binding,
                debug);
    }

    /**
     * File stem of {@code path} — what the run is called in listings, in
     * metrics and in the journal's {@code StartRecord}.
     *
     * <p>Package-private rather than private so the REST layer can answer
     * with the <em>same</em> name it journalled. Echoing the request path
     * back as {@code workflowName} handed clients a value that no
     * {@code ?workflow=<name>} query would ever match.
     */
    static String workflowNameFromPath(String path) {
        String stem = path.trim();
        stem = stem.substring(stem.lastIndexOf('/') + 1);
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
            @Nullable String parentState,
            MagratheaRunBinding binding,
            MagratheaDebugSetup debug) {
        Map<String, Object> resolvedParams = applyDefaultsAndValidate(workflow, callerParams);
        requireCapabilities(workflow, binding);
        requireKnownStates(workflow, debug.breakpoints());
        String runId = MagratheaRunIdGenerator.fresh();

        // Enqueue start on the lane and ACTUALLY await it, so the caller
        // sees the runId only after the start-records are journalled — and
        // a write failure propagates instead of leaving the caller with a
        // runId for a run that never materialised (code-review Phase 2).
        try {
            laneManager
                    .submitTracked(
                            projectId,
                            () -> writeStartRecords(
                                    tenantId,
                                    projectId,
                                    runId,
                                    workflow,
                                    resolvedParams,
                                    startedBy,
                                    sourcePath,
                                    parentMagratheaProcessId,
                                    parentState,
                                    binding,
                                    debug))
                    .get(START_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new MagratheaWorkflowException(
                    "Failed to start workflow '" + workflow.name() + "': " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new MagratheaWorkflowException("Timed out starting workflow '" + workflow.name() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MagratheaWorkflowException("Interrupted starting workflow '" + workflow.name() + "'", e);
        }

        metricService.counter(METRIC_STARTS, "workflow", workflow.name()).increment();
        return runId;
    }

    /**
     * Refuse a plan that contains a state this run could never execute.
     *
     * <p>Checked over <em>all</em> states, not the reachable ones: which
     * branch a run takes is not knowable at start, and a plan that works or
     * dies depending on the path is worse than one that is refused outright.
     * The cost of being conservative is a plan that has to declare its
     * intent; the cost of being permissive is a failure days later, on a
     * branch nobody was watching.
     *
     * <p>A state that catches {@code capability_missing} is exempt: it has
     * said what to do when the thing is impossible, so it is allowed to run
     * and find out.
     */
    private void requireCapabilities(ResolvedMagratheaWorkflow workflow, MagratheaRunBinding binding) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, MagratheaStateSpec> entry : workflow.states().entrySet()) {
            MagratheaStateSpec state = entry.getValue();
            if (state.catchKinds().containsKey(MagratheaErrorKind.CAPABILITY_MISSING)) {
                continue;
            }
            MagratheaTypeExecutor executor = taskExecutor.executorFor(state.type());
            if (executor == null) continue; // unknown type — the loader already rejects it
            for (RunCapability needed : executor.requires(state)) {
                if (!binding.has(needed)) {
                    problems.add("state '" + entry.getKey() + "' needs " + needed);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new MagratheaWorkflowException("Workflow '" + workflow.name() + "' cannot run with this binding: "
                    + String.join("; ", problems)
                    + ". Start it bound to a session/process, or declare "
                    + "catch: { capability_missing: <state> } on the state.");
        }
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
            log.info("Magrathea task {} timed out — closed agent process {}", task.getId(), subProcessId);
        } catch (RuntimeException ex) {
            log.warn(
                    "Magrathea task {} timed out but agent process {} could not be closed: {}",
                    task.getId(),
                    subProcessId,
                    ex.toString());
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
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    StatusRecord.builder()
                            .status(MagratheaRunStatus.PAUSED)
                            .reason("paused from the run view")
                            .build());
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
        Optional<StartRecord> start = journalService.readLast(tenantId, projectId, workflowRunId, StartRecord.class);
        boolean debugRun = start.isPresent() && MagratheaDebugSetup.isDebuggable(start.get());
        onLane(projectId, () -> {
            // A debug run that is resumed runs on: end the stepping before the
            // queue is released, so no completion can halt it again.
            if (debugRun) {
                journalService.append(
                        tenantId,
                        projectId,
                        workflowRunId,
                        DebugRecord.builder().stepping(false).build());
                endSteppingAbove(tenantId, projectId, start.get());
            }
            long released = releaseAndDispatch(workflowRunId);
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    StatusRecord.builder()
                            .status(MagratheaRunStatus.RUNNING)
                            .reason("resumed from the run view")
                            .build());
            log.info("Magrathea run {} resumed — {} task(s) released", workflowRunId, released);
        });
        return true;
    }

    /**
     * Debug runs: run exactly the next state, then halt again. Only a halted
     * debug run can be stepped; anything else is a no-op, because the button
     * was drawn from a snapshot the run may have moved on from.
     *
     * <p>The stepping is switched on first (a previous resume may have ended
     * it), then the queue is released, then the status flips — the same order
     * as {@link #resumeRun}, so a crash in between leaves a run that still
     * says PAUSED but makes progress.
     *
     * @return {@code true} if this call did the stepping
     */
    public boolean stepRun(String tenantId, String projectId, String workflowRunId) {
        if (currentStatus(tenantId, projectId, workflowRunId) != MagratheaRunStatus.PAUSED) return false;
        if (!isDebugRun(tenantId, projectId, workflowRunId)) return false;
        onLane(projectId, () -> {
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    DebugRecord.builder().stepping(true).build());
            long released = releaseAndDispatch(workflowRunId);
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    StatusRecord.builder()
                            .status(MagratheaRunStatus.RUNNING)
                            .reason("debug: step")
                            .build());
            log.info("Magrathea run {} stepped — {} task(s) released", workflowRunId, released);
        });
        return true;
    }

    /**
     * Whether the run was started debuggable — with the debug flag or with
     * breakpoints — so that it can be stepped and its breakpoints changed.
     */
    public boolean isDebugRun(String tenantId, String projectId, String workflowRunId) {
        return journalService
                .readLast(tenantId, projectId, workflowRunId, StartRecord.class)
                .map(MagratheaDebugSetup::isDebuggable)
                .orElse(false);
    }

    /**
     * Replace the states a debuggable run halts before. Takes effect for every
     * state the run enters from now on; a run already standing in front of a
     * state stays there until it is stepped or resumed.
     *
     * <p>Only runs started debuggable have breakpoints — an ordinary run pays
     * for none of this on its transitions — and only while the run is still
     * going.
     *
     * @return {@code true} if the set was replaced; {@code false} when the run
     *         is not debuggable or has ended
     * @throws IllegalArgumentException when a name is not a state of the run's
     *         frozen workflow
     */
    public boolean setBreakpoints(
            String tenantId, String projectId, String workflowRunId, java.util.Collection<String> names) {
        Optional<StartRecord> start = journalService.readLast(tenantId, projectId, workflowRunId, StartRecord.class);
        if (start.isEmpty() || !MagratheaDebugSetup.isDebuggable(start.get())) return false;
        MagratheaRunStatus status = currentStatus(tenantId, projectId, workflowRunId);
        if (status == MagratheaRunStatus.DONE
                || status == MagratheaRunStatus.FAILED
                || status == MagratheaRunStatus.TERMINATED) {
            return false;
        }
        Set<String> wanted = MagratheaDebugSetup.ordered(names);
        requireKnownStates(
                workflowLoader.validateYaml(
                        start.get().getWorkflowName(), start.get().getDefinitionYaml()),
                wanted);
        onLane(projectId, () -> {
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    BreakpointsRecord.builder()
                            .breakpoints(wanted)
                            .workflows(currentSubBreakpoints(tenantId, projectId, workflowRunId, start.get()))
                            .build());
            rememberForWorkflow(tenantId, projectId, start.get(), wanted);
        });
        log.info("Magrathea run {} breakpoints set to {}", workflowRunId, wanted);
        return true;
    }

    /**
     * Make a breakpoint set the workflow's: record it with every run above this
     * one that is still going, so the next sub-run of the same workflow — the
     * next element of a for-each — halts there too.
     */
    private void rememberForWorkflow(String tenantId, String projectId, StartRecord sub, Set<String> wanted) {
        forEachDebugAncestor(tenantId, projectId, sub, (ancestorId, ancestor) -> {
            Map<String, Set<String>> byWorkflow =
                    new java.util.LinkedHashMap<>(currentSubBreakpoints(tenantId, projectId, ancestorId, ancestor));
            byWorkflow.put(sub.getWorkflowName(), wanted);
            journalService.append(
                    tenantId,
                    projectId,
                    ancestorId,
                    BreakpointsRecord.builder()
                            .breakpoints(currentBreakpoints(tenantId, projectId, ancestorId, ancestor))
                            .workflows(MagratheaDebugSetup.orderedByWorkflow(byWorkflow))
                            .build());
        });
    }

    /**
     * A resume on a sub-run means "run on", not "run on until the next element
     * halts": end the stepping of the runs above it as well, or every further
     * sub-run would start stepping again, inherited from a parent that still is.
     */
    private void endSteppingAbove(String tenantId, String projectId, StartRecord sub) {
        forEachDebugAncestor(tenantId, projectId, sub, (ancestorId, ancestor) -> {
            if (isStepping(tenantId, projectId, ancestorId, ancestor)) {
                journalService.append(
                        tenantId,
                        projectId,
                        ancestorId,
                        DebugRecord.builder().stepping(false).build());
            }
        });
    }

    /**
     * Visit the runs above {@code sub}, nearest first, that are still going and
     * debuggable. An ancestor that is not debuggable or has ended is skipped, the
     * walk goes on past it.
     */
    private void forEachDebugAncestor(
            String tenantId,
            String projectId,
            StartRecord sub,
            java.util.function.BiConsumer<String, StartRecord> visit) {
        String ancestorId = sub.getParentMagratheaProcessId();
        int hops = 0;
        while (ancestorId != null && hops++ < MAX_ANCESTOR_HOPS) {
            Optional<StartRecord> ancestor =
                    journalService.readLast(tenantId, projectId, ancestorId, StartRecord.class);
            if (ancestor.isEmpty()) return;
            MagratheaRunStatus status = currentStatus(tenantId, projectId, ancestorId);
            boolean going = status != MagratheaRunStatus.DONE
                    && status != MagratheaRunStatus.FAILED
                    && status != MagratheaRunStatus.TERMINATED;
            if (going && MagratheaDebugSetup.isDebuggable(ancestor.get())) {
                visit.accept(ancestorId, ancestor.get());
            }
            ancestorId = ancestor.get().getParentMagratheaProcessId();
        }
    }

    /** Task types a debug reader can give a result instead of running them. */
    private static final Set<MagratheaTaskType> SIMULABLE = java.util.EnumSet.of(
            MagratheaTaskType.TOOL_TASK,
            MagratheaTaskType.SHELL_TASK,
            MagratheaTaskType.SCRIPT_TASK,
            MagratheaTaskType.AGENT_TASK,
            MagratheaTaskType.GATE_TASK,
            MagratheaTaskType.TIMER_TASK,
            MagratheaTaskType.WORKFLOW_TASK);

    /**
     * What a halted debug run could be given instead of executing its next state.
     *
     * @param state    the state the run stands in front of
     * @param taskType its task type
     * @param outcomes the outcomes the plan has a way on for
     */
    public record Simulation(String state, MagratheaTaskType taskType, java.util.List<String> outcomes) {}

    /**
     * The simulation a run offers right now, or empty when it offers none: the run
     * is not halted, not debuggable, stands in front of a state that has nothing to
     * replace (a condition is pure, a terminal ends the run), or the state has no
     * way on for any outcome.
     *
     * <p>Only a halted debuggable run is asked at all, so listing a run that is
     * going costs nothing.
     */
    public Optional<Simulation> simulationFor(String tenantId, String projectId, String workflowRunId) {
        if (currentStatus(tenantId, projectId, workflowRunId) != MagratheaRunStatus.PAUSED) return Optional.empty();
        Optional<StartRecord> start = journalService.readLast(tenantId, projectId, workflowRunId, StartRecord.class);
        if (start.isEmpty() || !MagratheaDebugSetup.isDebuggable(start.get())) return Optional.empty();
        Optional<MagratheaTaskDocument> held = heldTask(workflowRunId);
        if (held.isEmpty() || !SIMULABLE.contains(held.get().getTaskType())) return Optional.empty();
        MagratheaStateSpec state = workflowLoader
                .validateYaml(start.get().getWorkflowName(), start.get().getDefinitionYaml())
                .states()
                .get(held.get().getStateName());
        if (state == null) return Optional.empty();
        java.util.List<String> outcomes = outcomesOf(state);
        return outcomes.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        new Simulation(held.get().getStateName(), held.get().getTaskType(), outcomes));
    }

    /** The outcomes a state has a way on for: its {@code on:} keys, then its {@code catch:} error kinds. */
    private static java.util.List<String> outcomesOf(MagratheaStateSpec state) {
        Set<String> out = new java.util.LinkedHashSet<>(state.onOutcomes().keySet());
        state.catchKinds().keySet().forEach(kind -> out.add(kind.name().toLowerCase(Locale.ROOT)));
        return java.util.List.copyOf(out);
    }

    /** The task the halt is holding back, if there is exactly that one. */
    private Optional<MagratheaTaskDocument> heldTask(String workflowRunId) {
        return taskService.findByRun(workflowRunId).stream()
                .filter(t -> t.getStatus() == MagratheaTaskStatus.HELD)
                .findFirst();
    }

    /**
     * Give a halted debug run a result instead of executing the state it stands in
     * front of. The task never runs: the result is journalled as that of the task
     * (marked simulated), {@code storeAs:}, counters and transitions follow as for any
     * result, and — like after a step — the run halts again before the next state.
     *
     * <p>The order mirrors {@link #stepRun}: stepping is switched on, the run is set
     * running, and only then is the result handed in, because a run that still says
     * PAUSED would park the next task no matter what.
     *
     * @return {@code true} if the result was given; {@code false} when the run offers
     *         no simulation (see {@link #simulationFor}) or moved on in the meantime
     * @throws IllegalArgumentException when {@code outcome} is not one of the outcomes
     *         the state has a way on for — it would fail the run with "no transition"
     */
    public boolean simulateRun(
            String tenantId,
            String projectId,
            String workflowRunId,
            String outcome,
            @Nullable JsonNode output,
            @Nullable String errorMessage) {
        Optional<Simulation> offered = simulationFor(tenantId, projectId, workflowRunId);
        if (offered.isEmpty()) return false;
        String wanted = outcome == null ? "" : outcome.trim();
        if (!offered.get().outcomes().contains(wanted)) {
            throw new IllegalArgumentException("Outcome '" + wanted + "' leads nowhere in state '"
                    + offered.get().state() + "'; choose one of "
                    + offered.get().outcomes());
        }
        boolean[] given = {false};
        onLane(projectId, () -> {
            // The reader may have pressed Step in the meantime: only a still-held task can be simulated.
            Optional<MagratheaTaskDocument> held = heldTask(workflowRunId)
                    .filter(t -> t.getStateName().equals(offered.get().state()));
            if (held.isEmpty()) return;
            MagratheaTaskDocument task = held.get();
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    DebugRecord.builder().stepping(true).build());
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    TaskStartedRecord.builder()
                            .state(task.getStateName())
                            .taskType(task.getTaskType())
                            .taskId(task.getId())
                            .build());
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    StatusRecord.builder()
                            .status(MagratheaRunStatus.RUNNING)
                            .reason("debug: simulate '" + task.getStateName() + "'")
                            .build());
            handleCompletion(new TaskCompletedEvent(
                    tenantId,
                    projectId,
                    workflowRunId,
                    task.getId(),
                    task.getStateName(),
                    task.getTaskType(),
                    wanted,
                    output,
                    errorMessage,
                    0L,
                    null,
                    true));
            given[0] = true;
        });
        if (given[0]) {
            log.info(
                    "Magrathea run {} state '{}' simulated as '{}'",
                    workflowRunId,
                    offered.get().state(),
                    wanted);
        }
        return given[0];
    }

    /** Refuse a breakpoint on something that is not a state of the workflow. */
    private static void requireKnownStates(ResolvedMagratheaWorkflow workflow, Set<String> names) {
        for (String name : names) {
            if (!workflow.states().containsKey(name)) {
                throw new IllegalArgumentException(
                        "Unknown state '" + name + "' in breakpoints of workflow '" + workflow.name() + "'");
            }
        }
    }

    /** Why a debuggable run stands in front of a state. */
    private enum HaltCause {
        /** The run steps: it halts before every state. */
        STEPPING("debug"),
        /** The state is one of the run's breakpoints. */
        BREAKPOINT("breakpoint");

        final String label;

        HaltCause(String label) {
            this.label = label;
        }
    }

    /**
     * Whether, and why, a debuggable run halts before {@code stateName}. A run
     * that is not debuggable is not looked at, so an ordinary transition costs
     * no extra read.
     *
     * <p>Stepping wins over breakpoints: it halts everywhere, and a breakpoint
     * adds nothing there. A run started debuggable steps until a resume ends the
     * stepping; a step puts it back. A run started with breakpoints only does not
     * step until it is stepped.
     */
    private @Nullable HaltCause haltCauseFor(
            String tenantId, String projectId, String workflowRunId, StartRecord start, String stateName) {
        if (!MagratheaDebugSetup.isDebuggable(start)) return null;
        if (isStepping(tenantId, projectId, workflowRunId, start)) return HaltCause.STEPPING;
        return currentBreakpoints(tenantId, projectId, workflowRunId, start).contains(stateName)
                ? HaltCause.BREAKPOINT
                : null;
    }

    /** Whether the run halts before every state right now: the latest step/resume, else how it started. */
    private boolean isStepping(String tenantId, String projectId, String workflowRunId, StartRecord start) {
        return journalService
                .readLast(tenantId, projectId, workflowRunId, DebugRecord.class)
                .map(DebugRecord::isStepping)
                .orElse(start.isDebug());
    }

    /** The latest breakpoints for sub-run workflows of the run; before the first change, the ones it started with. */
    private Map<String, Set<String>> currentSubBreakpoints(
            String tenantId, String projectId, String workflowRunId, StartRecord start) {
        return journalService
                .readLast(tenantId, projectId, workflowRunId, BreakpointsRecord.class)
                .filter(r -> r.getWorkflows() != null)
                .map(r -> MagratheaDebugSetup.orderedByWorkflow(r.getWorkflows()))
                .orElseGet(() -> MagratheaDebugSetup.orderedByWorkflow(start.getSubBreakpoints()));
    }

    /** The latest breakpoint set of the run; before the first change, the one it started with. */
    private Set<String> currentBreakpoints(String tenantId, String projectId, String workflowRunId, StartRecord start) {
        return journalService
                .readLast(tenantId, projectId, workflowRunId, BreakpointsRecord.class)
                .map(r -> MagratheaDebugSetup.ordered(r.getBreakpoints()))
                .orElseGet(() -> MagratheaDebugSetup.ordered(start.getBreakpoints()));
    }

    /** Say, in the journal, that the run now stands before {@code stateName}. */
    private void haltBeforeState(
            String tenantId, String projectId, String workflowRunId, String stateName, HaltCause cause) {
        journalService.append(
                tenantId,
                projectId,
                workflowRunId,
                StatusRecord.builder()
                        .status(MagratheaRunStatus.PAUSED)
                        .reason(cause.label + ": before state '" + stateName + "'")
                        .build());
        log.info("Magrathea run {} halted before state '{}' ({})", workflowRunId, stateName, cause.label);
    }

    /**
     * Release the held queue of a run and hand the released tasks to the
     * local lane right away. Without the dispatch a released task waits for
     * the claimer's next scan, which would make every debug step lag.
     */
    private long releaseAndDispatch(String workflowRunId) {
        long released = taskService.releaseRun(workflowRunId);
        for (MagratheaTaskDocument task : taskService.findByRun(workflowRunId)) {
            if (task.getStatus() == MagratheaTaskStatus.PENDING) {
                localDispatch.dispatch(task);
            }
        }
        return released;
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
    public boolean failStalledRun(String tenantId, String projectId, String workflowRunId, String reason) {
        return endRun(tenantId, projectId, workflowRunId, MagratheaRunStatus.FAILED, reason);
    }

    private boolean endRun(
            String tenantId, String projectId, String workflowRunId, MagratheaRunStatus terminalStatus, String reason) {
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
                journalService.append(
                        tenantId,
                        projectId,
                        workflowRunId,
                        StatusRecord.builder()
                                .status(terminalStatus)
                                .reason(reason)
                                .build());
                recordTerminalMetrics(
                        workflowNameOf(tenantId, projectId, workflowRunId),
                        terminalStatus,
                        tenantId,
                        projectId,
                        workflowRunId);
                // Whoever owns this run finds out here or not at all: no
                // task completion follows a stop or a watchdog fail, so the
                // TERMINAL branch of onTaskCompleted never runs for it. A
                // stop that a Vogon issued on itself gets the message back,
                // which is harmless — the process is closing either way.
                //
                // The two ways in keep their meaning: a stop is a decision
                // somebody made, a stall is a defect, and an owner that is
                // told "failed" about its own stop would report it as one.
                ProcessEventType eventType = terminalStatus == MagratheaRunStatus.FAILED
                        ? ProcessEventType.FAILED
                        : ProcessEventType.STOPPED;
                journalService
                        .readLast(tenantId, projectId, workflowRunId, StartRecord.class)
                        .ifPresent(s -> ownerNotifier.runTerminated(
                                s.getOwnerProcessId(),
                                workflowRunId,
                                eventType,
                                "Workflow run " + terminalStatus.name().toLowerCase(Locale.ROOT) + ": " + reason));
            }
            // Says what was ended, not just what was blocked: "0 held" on a
            // run whose only task was already claimed reads like nothing
            // happened, when in fact an agent was just closed.
            log.info(
                    "Magrathea run {} → {} ({}) — {} queued task(s) held,"
                            + " {} in-flight task(s) unwound, opaque work remaining: {}",
                    workflowRunId,
                    terminalStatus,
                    reason,
                    held,
                    unwound,
                    stillWorking);
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
                log.warn("Magrathea stop: could not dismiss gate item '{}': {}", task.getInboxItemId(), ex.toString());
            }
            ended = true;
        }
        if (task.getSubProcessId() != null) {
            taskService.unlinkSubProcess(task.getId());
            try {
                thinkProcessService.closeProcess(task.getSubProcessId(), CloseReason.STOPPED);
            } catch (RuntimeException ex) {
                log.warn(
                        "Magrathea stop: could not close agent process '{}': {}",
                        task.getSubProcessId(),
                        ex.toString());
            }
            ended = true;
        }
        if (task.getSubWorkflowRunId() != null) {
            try {
                stopRun(tenantId, task.getProjectId(), task.getSubWorkflowRunId(), reason);
            } catch (RuntimeException ex) {
                log.warn("Magrathea stop: could not stop sub-run '{}': {}", task.getSubWorkflowRunId(), ex.toString());
            }
            ended = true;
        }
        if (task.getTaskType() == MagratheaTaskType.TIMER_TASK) {
            // The pending timer row is the whole of this task's work; endRun
            // deletes every timer of the run right after the unwind loop.
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
        return journalService
                .readLast(tenantId, projectId, workflowRunId, StartRecord.class)
                .map(StartRecord::getWorkflowName)
                .orElse("unknown");
    }

    /** Projected run status; {@code RUNNING} until a status record says otherwise. */
    public MagratheaRunStatus currentStatus(String tenantId, String projectId, String workflowRunId) {
        return journalService
                .readLast(tenantId, projectId, workflowRunId, StatusRecord.class)
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
            @Nullable String parentState,
            MagratheaRunBinding binding,
            MagratheaDebugSetup debug) {

        journalService.append(
                tenantId,
                projectId,
                runId,
                StartRecord.builder()
                        .workflowName(workflow.name())
                        .workflowVersion(workflow.version())
                        .definitionYaml(workflow.yaml())
                        .params(params)
                        .startedBy(startedBy)
                        .sourcePath(sourcePath)
                        .parentMagratheaProcessId(parentMagratheaProcessId)
                        .parentState(parentState)
                        .sessionId(binding.sessionId())
                        .ownerProcessId(binding.ownerProcessId())
                        .capabilities(binding.capabilities().stream()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)))
                        .derivedParamKeys(
                                binding.derivedParamKeys().isEmpty()
                                        ? null
                                        : new java.util.LinkedHashSet<>(binding.derivedParamKeys()))
                        .debug(debug.stepping())
                        .breakpoints(
                                debug.breakpoints().isEmpty()
                                        ? null
                                        : new java.util.LinkedHashSet<>(debug.breakpoints()))
                        .subBreakpoints(debug.workflowBreakpoints().isEmpty() ? null : debug.workflowBreakpoints())
                        .build());

        journalService.append(
                tenantId,
                projectId,
                runId,
                StateEnteredRecord.builder().state(workflow.startState()).build());

        MagratheaStateSpec startState = workflow.states().get(workflow.startState());
        applyCounters(tenantId, projectId, runId, startState);
        // A debug run stands before its first state when it steps or has a
        // breakpoint there: say so first, then park the task — the order
        // enqueueNextTask uses for every later one.
        HaltCause firstHalt = debug.stepping()
                ? HaltCause.STEPPING
                : debug.breakpoints().contains(workflow.startState()) ? HaltCause.BREAKPOINT : null;
        if (firstHalt != null) {
            haltBeforeState(tenantId, projectId, runId, workflow.startState(), firstHalt);
        }
        MagratheaTaskDocument task = MagratheaTaskDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .workflowRunId(runId)
                .workflowName(workflow.name())
                .stateName(workflow.startState())
                .taskType(startState.type())
                .status(firstHalt != null ? MagratheaTaskStatus.HELD : MagratheaTaskStatus.PENDING)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .attemptCount(0)
                .build();
        localDispatch.dispatch(taskService.insert(task));
        log.info(
                "Magrathea run {} started workflow='{}' tenant={} project={} startState={} debug={} breakpoints={}",
                runId,
                workflow.name(),
                tenantId,
                projectId,
                workflow.startState(),
                debug.stepping(),
                debug.breakpoints());
    }

    private Map<String, Object> applyDefaultsAndValidate(
            ResolvedMagratheaWorkflow workflow, @Nullable Map<String, Object> callerParams) {
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
                        "Required parameter '" + key + "' missing for workflow '" + workflow.name() + "'");
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
                        .simulated(event.simulated())
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
        Optional<StartRecord> start =
                journalService.readLast(event.tenantId(), event.projectId(), event.workflowRunId(), StartRecord.class);
        if (start.isEmpty()) {
            log.error("Magrathea onTaskCompleted: run {} has no StartRecord", event.workflowRunId());
            markTaskTerminal(task, event.outcome());
            return;
        }

        ResolvedMagratheaWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(
                    start.get().getWorkflowName(), start.get().getDefinitionYaml());
        } catch (RuntimeException ex) {
            log.error("Magrathea onTaskCompleted: cannot re-parse frozen YAML for run {}", event.workflowRunId(), ex);
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
                    event.tenantId(),
                    event.projectId(),
                    event.workflowRunId(),
                    VarRecord.builder()
                            .key(state.storeAs())
                            .value(event.output())
                            .build());
        }

        // 2. TERMINAL-specific: write StatusRecord + ResultRecord
        if (event.taskType() == MagratheaTaskType.TERMINAL) {
            MagratheaRunStatus runStatus = TaskCompletedEvent.OUTCOME_SUCCESS.equals(event.outcome())
                    ? MagratheaRunStatus.DONE
                    : MagratheaRunStatus.FAILED;
            if (event.output() != null) {
                journalService.append(
                        event.tenantId(),
                        event.projectId(),
                        event.workflowRunId(),
                        ResultRecord.builder()
                                .state(event.stateName())
                                .result(event.output())
                                .build());
            }
            journalService.append(
                    event.tenantId(),
                    event.projectId(),
                    event.workflowRunId(),
                    StatusRecord.builder()
                            .status(runStatus)
                            .reason(event.errorMessage())
                            .build());
            markTaskTerminal(task, event.outcome());

            recordTerminalMetrics(
                    start.get().getWorkflowName(),
                    runStatus,
                    event.tenantId(),
                    event.projectId(),
                    event.workflowRunId());

            // Surface to any parent that's waiting on this run as a sub-workflow.
            publishWorkflowCompleted(event, start.get(), runStatus);

            // And to the process that owns it, if any. It reads the result
            // from the journal itself — this only tells it there is one.
            ownerNotifier.runTerminated(
                    start.get().getOwnerProcessId(),
                    event.workflowRunId(),
                    runStatus == MagratheaRunStatus.DONE ? ProcessEventType.DONE : ProcessEventType.FAILED,
                    terminalSummary(event, runStatus));
            log.info(
                    "Magrathea run {} reached terminal '{}' → {}", event.workflowRunId(), event.stateName(), runStatus);
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
        Optional<String> boundsViolation =
                checkBounds(event.tenantId(), event.projectId(), event.workflowRunId(), workflow);
        if (boundsViolation.isPresent()) {
            log.warn("Magrathea run {} bounds exhausted: {}", event.workflowRunId(), boundsViolation.get());
            markTaskTerminal(task, event.outcome());
            writeRunFailed(event, "bounds exhausted: " + boundsViolation.get());
            return;
        }

        // 5. Non-terminal — resolve next state and enqueue
        String nextState = resolveNextState(state, event);
        markTaskTerminal(task, event.outcome());

        if (nextState == null) {
            // No transition matches and no catch matches — treat as run failure.
            log.warn(
                    "Magrathea run {} state '{}' produced outcome '{}' with no transition",
                    event.workflowRunId(),
                    event.stateName(),
                    event.outcome());
            writeRunFailed(event, "no transition for outcome '" + event.outcome() + "'");
            return;
        }
        enqueueNextTask(event, workflow, nextState, start.get());
    }

    private static boolean canRetry(MagratheaStateSpec state, MagratheaTaskDocument task, TaskCompletedEvent event) {
        // A retry would run the task for real — the opposite of what a simulated result is for.
        if (event.simulated()) return false;
        if (state.retry() == null) return false;
        MagratheaErrorKind kind = parseErrorKind(event.outcome());
        if (kind == null) return false;
        if (!state.retry().onErrorKinds().contains(kind)) return false;
        // maxAttempts counts the original attempt + retries.
        int nextRetryCount = task.getRetryCount() + 1;
        return nextRetryCount < state.retry().maxAttempts();
    }

    private void enqueueRetry(
            TaskCompletedEvent prev, ResolvedMagratheaWorkflow workflow, MagratheaStateSpec state, int retryCount) {
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
        // Dispatched locally only when the back-off has already elapsed
        // (backoff == 0); otherwise the claimer picks it up when it is due.
        localDispatch.dispatch(taskService.insert(retry));
        log.info(
                "Magrathea run {} state '{}' retry {} scheduled (backoff={}s)",
                prev.workflowRunId(),
                prev.stateName(),
                retryCount,
                backoff);
    }

    private Optional<String> checkBounds(
            String tenantId, String projectId, String workflowRunId, ResolvedMagratheaWorkflow workflow) {
        MagratheaBoundsSpec bounds = workflow.bounds();
        if (bounds == null) return Optional.empty();

        if (bounds.maxWallclockSeconds() != null) {
            Optional<Instant> runStart = journalService.firstCreatedAt(tenantId, projectId, workflowRunId);
            if (runStart.isPresent()) {
                long elapsed = java.time.Duration.between(runStart.get(), Instant.now())
                        .getSeconds();
                if (elapsed > bounds.maxWallclockSeconds()) {
                    return Optional.of("maxWallclockSeconds=" + bounds.maxWallclockSeconds() + " exceeded (elapsed "
                            + elapsed + "s)");
                }
            }
        }
        if (bounds.maxTaskSpawns() != null) {
            long spawned = journalService.count(
                    tenantId, projectId, workflowRunId, de.mhus.vance.shared.magrathea.journal.TaskStartedRecord.class);
            if (spawned > bounds.maxTaskSpawns()) {
                return Optional.of("maxTaskSpawns=" + bounds.maxTaskSpawns() + " exceeded (started " + spawned + ")");
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
            TaskCompletedEvent prev, ResolvedMagratheaWorkflow workflow, String nextStateName, StartRecord start) {
        MagratheaStateSpec next = workflow.states().get(nextStateName);
        if (next == null) {
            log.error(
                    "Magrathea run {} transition target '{}' missing from workflow",
                    prev.workflowRunId(),
                    nextStateName);
            writeRunFailed(prev, "transition target missing: " + nextStateName);
            return;
        }
        journalService.append(
                prev.tenantId(),
                prev.projectId(),
                prev.workflowRunId(),
                StateEnteredRecord.builder().state(nextStateName).build());
        applyCounters(prev.tenantId(), prev.projectId(), prev.workflowRunId(), next);
        // A debuggable run halts here, before the new state runs, when it is
        // stepping or the state is one of its breakpoints.
        HaltCause haltCause =
                haltCauseFor(prev.tenantId(), prev.projectId(), prev.workflowRunId(), start, nextStateName);
        boolean halted = haltCause != null;
        if (halted
                && currentStatus(prev.tenantId(), prev.projectId(), prev.workflowRunId())
                        != MagratheaRunStatus.PAUSED) {
            haltBeforeState(prev.tenantId(), prev.projectId(), prev.workflowRunId(), nextStateName, haltCause);
        }
        // A task created while the run is paused must not slip past the
        // hold: pausing moved the queue to HELD, but this row is new.
        boolean paused = halted
                || currentStatus(prev.tenantId(), prev.projectId(), prev.workflowRunId()) == MagratheaRunStatus.PAUSED;
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
        localDispatch.dispatch(taskService.insert(task));
    }

    /**
     * Apply a state's {@code resetCounters:} and {@code enterCounter:} as
     * ordinary variable writes, at the moment the state is entered.
     *
     * <p>Written as {@code VarRecord}s rather than kept somewhere of their
     * own, so a counter is readable exactly like every other variable —
     * {@code #state['rounds']} in a condition, {@code ${state.rounds}} in a
     * prompt — and shows up in the run's variables without a second
     * projection knowing about it.
     *
     * <p>Order matters: resets first. A state that both begins a section and
     * is its first step can therefore say {@code resetCounters: [rounds]}
     * plus {@code enterCounter: rounds} and land on 1, not 0.
     */
    private void applyCounters(
            String tenantId, String projectId, String workflowRunId, @Nullable MagratheaStateSpec state) {
        if (state == null) return;

        for (String name : state.resetCounters()) {
            journalService.append(
                    tenantId,
                    projectId,
                    workflowRunId,
                    VarRecord.builder()
                            .key(name)
                            .value(tools.jackson.databind.node.LongNode.valueOf(0L))
                            .build());
        }
        String counter = state.enterCounter();
        if (counter == null || counter.isBlank()) return;

        Map<String, Object> vars = projectorVars(tenantId, projectId, workflowRunId);
        long next = asLong(vars.get(counter)) + 1;
        journalService.append(
                tenantId,
                projectId,
                workflowRunId,
                VarRecord.builder()
                        .key(counter)
                        .value(tools.jackson.databind.node.LongNode.valueOf(next))
                        .build());
        log.debug("Magrathea run {} state '{}' counter '{}' = {}", workflowRunId, state.name(), counter, next);
    }

    /**
     * Current value of a counter variable. Anything that is not a number —
     * a variable the plan also uses for something else — restarts the count
     * rather than failing the run: a broken count is a plan bug to see in
     * the journal, not a reason to kill a run mid-flight.
     */
    private static long asLong(@Nullable Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof CharSequence cs) {
            try {
                return Long.parseLong(cs.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private Map<String, Object> projectorVars(String tenantId, String projectId, String workflowRunId) {
        return stateProjector.projectVars(tenantId, projectId, workflowRunId);
    }

    /**
     * One line about how the run ended, for the owner to show.
     *
     * <p>Not the result itself: the {@code ResultRecord} is in the journal
     * and the owner reads it there, typed. What travels here is only enough
     * for a human to know what happened without opening anything.
     */
    private static @Nullable String terminalSummary(TaskCompletedEvent event, MagratheaRunStatus status) {
        if (status != MagratheaRunStatus.DONE) {
            return event.errorMessage() != null && !event.errorMessage().isBlank()
                    ? "Workflow run failed: " + event.errorMessage()
                    : "Workflow run failed at '" + event.stateName() + "'";
        }
        return "Workflow run finished at '" + event.stateName() + "'";
    }

    private void markTaskTerminal(MagratheaTaskDocument task, String outcome) {
        if (TaskCompletedEvent.OUTCOME_SUCCESS.equals(outcome)) {
            taskService.markDone(task.getId());
        } else {
            taskService.markFailed(task.getId());
        }
    }

    private void writeRunFailed(TaskCompletedEvent event, String reason) {
        journalService.append(
                event.tenantId(),
                event.projectId(),
                event.workflowRunId(),
                StatusRecord.builder()
                        .status(MagratheaRunStatus.FAILED)
                        .reason(reason)
                        .build());
        Optional<StartRecord> start =
                journalService.readLast(event.tenantId(), event.projectId(), event.workflowRunId(), StartRecord.class);
        String workflowName = start.map(StartRecord::getWorkflowName).orElse("unknown");
        recordTerminalMetrics(
                workflowName, MagratheaRunStatus.FAILED, event.tenantId(), event.projectId(), event.workflowRunId());
        // Surface this non-terminal failure to any parent waiting on the run as a
        // sub-workflow. Only onTaskCompleted's TERMINAL branch publishes the
        // completion event, and there is no WAITING_SUBWORKFLOW recovery scan —
        // so without this a parent workflow_task hangs forever when its sub-run
        // fails via bounds-exhaustion / no-matching-transition / missing target /
        // unparseable frozen YAML. For a top-level run (no parent in the
        // StartRecord) the listener no-ops, so this is safe to fire always.
        start.ifPresent(s -> publishWorkflowCompleted(event, s, MagratheaRunStatus.FAILED));
        // The same argument for the process that owns the run. A parent
        // workflow_task and an owning ThinkProcess are two different waiters
        // on the same event, and telling only the first leaves a Vogon
        // process idling forever behind a run that already ended.
        start.ifPresent(s -> ownerNotifier.runTerminated(
                s.getOwnerProcessId(),
                event.workflowRunId(),
                ProcessEventType.FAILED,
                "Workflow run failed: " + reason));
    }

    /**
     * Counter + duration for a terminal status transition. Duration is
     * measured from the StartRecord entry's {@code createdAt} to now —
     * if the StartRecord can't be located (shouldn't happen on healthy
     * paths) the timer is skipped, the counter still fires.
     */
    private void recordTerminalMetrics(
            String workflowName, MagratheaRunStatus status, String tenantId, String projectId, String workflowRunId) {
        metricService
                .counter(METRIC_TERMINATIONS, "workflow", workflowName, "status", status.name())
                .increment();
        Instant startedAt = findStartInstant(tenantId, projectId, workflowRunId);
        if (startedAt != null) {
            metricService
                    .timer(METRIC_DURATION, "workflow", workflowName, "status", status.name())
                    .record(Duration.between(startedAt, Instant.now()));
        }
    }

    /** Walks the journal in createdAt order; the first entry is the StartRecord. */
    private @Nullable Instant findStartInstant(String tenantId, String projectId, String workflowRunId) {
        List<MagratheaJournalEntry> entries = journalService.read(tenantId, projectId, workflowRunId);
        if (entries.isEmpty()) return null;
        return entries.get(0).getCreatedAt();
    }

    private void publishWorkflowCompleted(TaskCompletedEvent event, StartRecord start, MagratheaRunStatus status) {
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
