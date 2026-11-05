package de.mhus.vance.brain.runs;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunChildDto;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunLinkDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Magrathea workflow runs as {@link RunSource}.
 *
 * <p>Everything here is a projection of the append-only journal, which is
 * the authoritative record: the steps come from the entered-state and
 * task-result entries in order, the variables from the existing state
 * projector. Nothing is cached — a run view that showed a stale status
 * would be worse than a slow one.
 *
 * <p>Only registered when Magrathea is switched on; the registry simply
 * has one source fewer otherwise.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaRunSource implements RunSource {

    public static final String SOURCE_ID = "workflow";

    /**
     * Longest step output sent to the view. The journal keeps the whole
     * result; the view needs enough to read what happened, not an agent's
     * full transcript on every refresh.
     */
    private static final int OUTPUT_MAX_CHARS = 20_000;

    /** How far back {@link #readChildren} looks for sub-runs of one run. */
    private static final int CHILD_SCAN_LIMIT = 200;

    private final MagratheaJournalService journalService;
    private final MagratheaStateProjector projector;
    /** Owns pause/resume/stop; the source only maps state to verbs. */
    private final MagratheaWorkflowService workflowService;
    /** Whose session a bound run belongs to — see visibleTo. */
    private final de.mhus.vance.shared.session.SessionService sessionService;

    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    /** Finds the gate a waiting run sits at — see {@link #waitingOnInboxItem}. */
    private final MagratheaGateChatAnswerService gateAnswers;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RunSummaryDto> list(String tenantId, String projectId, int limit) {
        List<RunSummaryDto> out = new ArrayList<>();
        for (String runId : journalService.listRunIds(tenantId, projectId, limit)) {
            projector.project(tenantId, projectId, runId).map(this::toSummary).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public Optional<RunDetailDto> get(String tenantId, String projectId, String nativeId) {
        Optional<MagratheaProcessDto> dto = projector.project(tenantId, projectId, nativeId);
        if (dto.isEmpty()) return Optional.empty();
        MagratheaProcessDto run = dto.get();
        // Cross-check like the workflow controller: a run of another scope
        // is reported as absent rather than as forbidden, so the view
        // cannot probe for existence.
        if (!tenantId.equals(run.getTenantId()) || !projectId.equals(run.getProjectId())) {
            return Optional.empty();
        }

        Optional<StartRecord> start = journalService.readLast(tenantId, projectId, nativeId, StartRecord.class);
        return Optional.of(RunDetailDto.builder()
                .summary(toSummary(run))
                .steps(readSteps(tenantId, projectId, nativeId))
                .variables(run.getVars() == null ? new LinkedHashMap<>() : run.getVars())
                .children(readChildren(tenantId, projectId, nativeId))
                .links(readLinks(start, run))
                .definition(start.map(StartRecord::getDefinitionYaml).orElse(null))
                .waitingOnInboxItemId(waitingOnInboxItem(tenantId, nativeId, run.getStatus()))
                .allowedActions(actionsFor(run.getStatus(), run.isDebug()))
                .debug(run.isDebug())
                .errorMessage(terminalReason(tenantId, projectId, nativeId, run.getStatus()))
                .result(run.getResult())
                .extra(Map.of("params", run.getParams() == null ? Map.of() : run.getParams()))
                .build());
    }

    @Override
    public Set<RunAction> allowedActions(String tenantId, String projectId, String nativeId) {
        return projector
                .project(tenantId, projectId, nativeId)
                .filter(r -> tenantId.equals(r.getTenantId()) && projectId.equals(r.getProjectId()))
                .map(r -> actionsFor(r.getStatus(), r.isDebug()))
                .orElseGet(Set::of);
    }

    private static Set<RunAction> actionsFor(@Nullable MagratheaRunStatus status, boolean debug) {
        if (status == null) return Set.of(RunAction.PAUSE, RunAction.STOP);
        return switch (status) {
            case RUNNING -> Set.of(RunAction.PAUSE, RunAction.STOP);
            // A halted debug run can be stepped; Resume then runs it on.
            case PAUSED ->
                debug
                        ? Set.of(RunAction.STEP, RunAction.RESUME, RunAction.STOP)
                        : Set.of(RunAction.RESUME, RunAction.STOP);
            case DONE, FAILED, TERMINATED -> Set.of();
        };
    }

    @Override
    public void perform(String tenantId, String projectId, String nativeId, RunAction action, String reason) {
        MagratheaProcessDto run = projector
                .project(tenantId, projectId, nativeId)
                .filter(r -> tenantId.equals(r.getTenantId()) && projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + nativeId));
        // Not-applicable is a no-op: the button came from a snapshot and
        // the run may have moved on in the meantime.
        if (!actionsFor(run.getStatus(), run.isDebug()).contains(action)) return;
        switch (action) {
            case PAUSE -> workflowService.pauseRun(tenantId, projectId, nativeId);
            case RESUME -> workflowService.resumeRun(tenantId, projectId, nativeId);
            case STEP -> workflowService.stepRun(tenantId, projectId, nativeId);
            case STOP -> workflowService.stopRun(tenantId, projectId, nativeId, reason);
        }
    }

    private RunSummaryDto toSummary(MagratheaProcessDto run) {
        return RunSummaryDto.builder()
                .runId(RunId.of(SOURCE_ID, run.getWorkflowRunId()).composite())
                .source(SOURCE_ID)
                .name(run.getWorkflowName())
                .status(mapStatus(run.getStatus()))
                .step(run.getCurrentState())
                .projectId(run.getProjectId())
                .startedBy(run.getStartedBy())
                .startedAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    /**
     * Magrathea's five statuses onto the shared vocabulary, one for one.
     *
     * <p><b>{@code WAITING} is never produced here</b>, and that is a
     * known gap rather than an oversight: a run sitting on a gate or a
     * timer is {@code RUNNING} with a task that waits, and which of the
     * two it is only shows on the task row — which the journal projection
     * deliberately does not carry. Reconstructing it from the current
     * state's name would be a guess, and a wrong "waiting" reads worse
     * than an honest "running".
     */
    private static RunStatus mapStatus(@Nullable MagratheaRunStatus status) {
        if (status == null) return RunStatus.RUNNING;
        return switch (status) {
            case RUNNING -> RunStatus.RUNNING;
            case PAUSED -> RunStatus.PAUSED;
            case DONE -> RunStatus.DONE;
            case FAILED -> RunStatus.FAILED;
            case TERMINATED -> RunStatus.STOPPED;
        };
    }

    /**
     * Why the run ended, from the terminal {@code StatusRecord}.
     *
     * <p>Without this a stopped or failed run is simply over, with no
     * trace of what did it — and the three ways a run can end without
     * finishing (somebody pressed stop, a deadline fired, the watchdog
     * found it motionless) are exactly the ones a reader needs told
     * apart. Only terminal runs carry it; a paused run's reason is
     * visible in its status already.
     */
    private @Nullable String terminalReason(
            String tenantId, String projectId, String runId, @Nullable MagratheaRunStatus status) {
        if (status != MagratheaRunStatus.FAILED && status != MagratheaRunStatus.TERMINATED) {
            return null;
        }
        return journalService
                .readLast(tenantId, projectId, runId, de.mhus.vance.shared.magrathea.journal.StatusRecord.class)
                .map(de.mhus.vance.shared.magrathea.journal.StatusRecord::getReason)
                .orElse(null);
    }

    /**
     * The inbox item this run is waiting at, when it is waiting at one.
     *
     * <p>{@code runs-view.md} §4.2 promises this field for Magrathea and
     * nothing produced it, which made the one link a reader opens the detail
     * page <em>for</em> — "it says waiting; waiting on what?" — impossible to
     * render. The answer already existed: {@code findOpenGateItem} is what
     * the chat route uses to decide whether an utterance is an answer.
     *
     * <p>Only asked of a live run: a finished one has no open gate, and the
     * two extra reads per detail view are not worth spending to prove it.
     * A failure to look is not a failure of the page — the field stays empty
     * and the rest of the detail renders, which is the same shape the field
     * has when there is simply no gate.
     */
    private @Nullable String waitingOnInboxItem(String tenantId, String runId, @Nullable MagratheaRunStatus status) {
        if (status != MagratheaRunStatus.RUNNING && status != MagratheaRunStatus.PAUSED) {
            return null;
        }
        try {
            return gateAnswers
                    .findOpenGateItem(tenantId, runId)
                    .map(de.mhus.vance.shared.inbox.MaximegalonDocument::getId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Magrathea run {} — could not look up its open gate: {}", runId, e.toString());
            return null;
        }
    }

    /**
     * Steps in journal order: every entered state, with the task that ran in it.
     *
     * <p>The journal is a flat event log, so the list is rebuilt by walking it
     * once: an entered state opens a step, and the task records that follow for
     * the same state fill it in — task type, duration, outcome and output. A
     * state entered twice (a loop) therefore gets two steps with their own
     * results, where pairing by state name alone would hand the last result to
     * both. When a state has several results (a retry) the last one wins.
     */
    private List<RunStepDto> readSteps(String tenantId, String projectId, String runId) {
        List<RunStepDto> steps = new ArrayList<>();
        Map<String, RunStepDto> latestByState = new LinkedHashMap<>();
        for (MagratheaJournalEntry entry : journalService.read(tenantId, projectId, runId)) {
            Optional<JournalRecord> record = journalService.toRecord(entry);
            if (record.isEmpty()) continue;
            if (record.get() instanceof StateEnteredRecord entered) {
                RunStepDto step = RunStepDto.builder()
                        .name(entered.getState())
                        .kind("state")
                        .at(entry.getCreatedAt())
                        .build();
                steps.add(step);
                latestByState.put(entered.getState(), step);
            } else if (record.get() instanceof TaskStartedRecord started) {
                RunStepDto step = latestByState.get(started.getState());
                if (step != null && started.getTaskType() != null) {
                    step.setKind(started.getTaskType().name().toLowerCase(Locale.ROOT));
                }
            } else if (record.get() instanceof TaskResultRecord result) {
                RunStepDto step = latestByState.get(result.getState());
                if (step != null) fill(step, result);
            }
        }
        return steps;
    }

    private static void fill(RunStepDto step, TaskResultRecord result) {
        step.setOutcome(result.getOutcome());
        step.setDetail(result.getErrorMessage());
        step.setDurationMs(result.getDurationMs());
        JsonNode output = result.getOutput();
        if (output != null && !output.isNull()) {
            String text = output.isString() ? output.asString() : output.toPrettyString();
            boolean cut = text.length() > OUTPUT_MAX_CHARS;
            step.setOutput(cut ? text.substring(0, OUTPUT_MAX_CHARS) : text);
            step.setOutputTruncated(cut);
        }
    }

    /**
     * Sub-runs, found through the parent pointer the start record carries.
     *
     * <p>One query: {@code listRunStarts} hands back the records it has
     * already loaded. Asking for ids and then reading each run's start
     * record back was {@code 1 + n} round-trips on every single detail
     * view, for a relation that is one field on a record already in hand.
     *
     * <p>The {@code CHILD_SCAN_LIMIT} window is the honest part: there is
     * no index on the parent pointer, so this finds children among the
     * project's most recent runs and nothing older.
     */
    private List<RunChildDto> readChildren(String tenantId, String projectId, String runId) {
        List<RunChildDto> children = new ArrayList<>();
        for (MagratheaJournalService.RunStart candidate :
                journalService.listRunStarts(tenantId, projectId, CHILD_SCAN_LIMIT)) {
            if (candidate.workflowRunId().equals(runId)) continue;
            StartRecord start = candidate.start();
            if (!runId.equals(start.getParentMagratheaProcessId())) continue;
            children.add(RunChildDto.builder()
                    .runId(RunId.of(SOURCE_ID, candidate.workflowRunId()).composite())
                    .name(start.getWorkflowName())
                    .fromStep(start.getParentState())
                    .build());
        }
        return children;
    }

    /**
     * Where to go from here: the definition this run froze, and — when the
     * run belongs to somebody — the session it belongs to.
     *
     * <p>The session link is what keeps a Vogon run findable now that it no
     * longer appears as a process of its own. Without it a plan someone
     * started in a conversation would sit in the list with no way back to
     * the conversation that asked for it.
     */
    private List<RunLinkDto> readLinks(Optional<StartRecord> start, MagratheaProcessDto run) {
        List<RunLinkDto> links = new ArrayList<>(2);
        String path = start.map(StartRecord::getSourcePath).orElse(null);
        if (path == null && run.getWorkflowName() != null) {
            path = MagratheaWorkflowLoader.WORKFLOW_PATH_PREFIX
                    + run.getWorkflowName()
                    + MagratheaWorkflowLoader.WORKFLOW_PATH_SUFFIX;
        }
        if (path != null) {
            links.add(RunLinkDto.builder()
                    .rel("definition")
                    .label(path)
                    .target(path)
                    .build());
        }
        String sessionId = start.map(StartRecord::getSessionId).orElse(null);
        if (sessionId != null && !sessionId.isBlank()) {
            links.add(RunLinkDto.builder()
                    .rel("session")
                    .label(sessionId)
                    .target(sessionId)
                    .build());
        }
        return List.copyOf(links);
    }

    /**
     * A run bound to a session sits behind something narrower than the
     * project, and the project check the caller already passed does not
     * cover it: a plan someone is running inside their own conversation is
     * not everyone's to read just because they share the project.
     *
     * <p>Three ways in, in the order they are cheap: the run belongs to
     * nobody (project-scoped, as every scheduler and event run is), the
     * caller owns the session, or the caller administers the project.
     * A session that has since been deleted falls back to visible — the
     * run's own project scoping still held, and hiding history because a
     * session record was cleaned up would be a surprise, not a safeguard.
     */
    @Override
    public boolean visibleTo(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId,
            String projectId,
            String nativeId) {
        String sessionId = journalService
                .readLast(tenantId, projectId, nativeId, StartRecord.class)
                .map(StartRecord::getSessionId)
                .orElse(null);
        if (sessionId == null || sessionId.isBlank()) return true;

        return sessionService
                .findBySessionId(sessionId)
                .map(session -> session.isSystem()
                        || (subject != null && subject.subjectId().equals(session.getUserId()))
                        || permissionService.check(
                                subject,
                                new de.mhus.vance.shared.permission.Resource.Project(tenantId, projectId),
                                de.mhus.vance.shared.permission.Action.ADMIN))
                .orElse(true);
    }
}
