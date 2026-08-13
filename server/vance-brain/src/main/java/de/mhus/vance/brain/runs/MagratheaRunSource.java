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
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaRunSource implements RunSource {

    public static final String SOURCE_ID = "workflow";

    private final MagratheaJournalService journalService;
    private final MagratheaStateProjector projector;
    /** Owns pause/resume/stop; the source only maps state to verbs. */
    private final MagratheaWorkflowService workflowService;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RunSummaryDto> list(String tenantId, String projectId, int limit) {
        List<RunSummaryDto> out = new ArrayList<>();
        for (String runId : journalService.listRunIds(tenantId, projectId, limit)) {
            projector.project(tenantId, projectId, runId)
                    .map(this::toSummary)
                    .ifPresent(out::add);
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

        return Optional.of(RunDetailDto.builder()
                .summary(toSummary(run))
                .steps(readSteps(tenantId, projectId, nativeId))
                .variables(run.getVars() == null ? new LinkedHashMap<>() : run.getVars())
                .children(readChildren(tenantId, projectId, nativeId))
                .links(readLinks(tenantId, projectId, nativeId, run))
                .allowedActions(actionsFor(run.getStatus()))
                .result(run.getResult())
                .extra(Map.of("params", run.getParams() == null ? Map.of() : run.getParams()))
                .build());
    }

    @Override
    public Set<RunAction> allowedActions(String tenantId, String projectId, String nativeId) {
        return projector.project(tenantId, projectId, nativeId)
                .filter(r -> tenantId.equals(r.getTenantId()) && projectId.equals(r.getProjectId()))
                .map(r -> actionsFor(r.getStatus()))
                .orElseGet(Set::of);
    }

    private static Set<RunAction> actionsFor(@Nullable MagratheaRunStatus status) {
        if (status == null) return Set.of(RunAction.PAUSE, RunAction.STOP);
        return switch (status) {
            case RUNNING -> Set.of(RunAction.PAUSE, RunAction.STOP);
            case PAUSED -> Set.of(RunAction.RESUME, RunAction.STOP);
            case DONE, FAILED, TERMINATED -> Set.of();
        };
    }

    @Override
    public void perform(String tenantId, String projectId, String nativeId,
                        RunAction action, String reason) {
        MagratheaProcessDto run = projector.project(tenantId, projectId, nativeId)
                .filter(r -> tenantId.equals(r.getTenantId()) && projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + nativeId));
        // Not-applicable is a no-op: the button came from a snapshot and
        // the run may have moved on in the meantime.
        if (!actionsFor(run.getStatus()).contains(action)) return;
        switch (action) {
            case PAUSE -> workflowService.pauseRun(tenantId, projectId, nativeId);
            case RESUME -> workflowService.resumeRun(tenantId, projectId, nativeId);
            case STOP -> workflowService.stopRun(tenantId, projectId, nativeId, reason);
        }
    }

    private RunSummaryDto toSummary(MagratheaProcessDto run) {
        return RunSummaryDto.builder()
                .runId(RunId.of(SOURCE_ID, run.getWorkflowRunId()).composite())
                .source(SOURCE_ID)
                .name(run.getWorkflowName())
                .status(mapStatus(run.getStatus(), run.getCurrentState()))
                .step(run.getCurrentState())
                .projectId(run.getProjectId())
                .startedBy(run.getStartedBy())
                .startedAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    /**
     * Magrathea has no {@code WAITING} of its own — a run sitting on a
     * gate or a timer is {@code RUNNING} with a task that waits. The
     * distinction matters to a reader, so it is reconstructed from the
     * current state's name being present while nothing advances; anything
     * finer would need the task row, which the projection deliberately
     * does not carry.
     */
    private static RunStatus mapStatus(@Nullable MagratheaRunStatus status, @Nullable String state) {
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
     * Steps in journal order: every entered state, closed off by the task
     * result that ended it. The journal is a flat event log, so the pairing
     * happens here rather than in storage.
     */
    private List<RunStepDto> readSteps(String tenantId, String projectId, String runId) {
        List<StateEnteredRecord> entered =
                journalService.readAll(tenantId, projectId, runId, StateEnteredRecord.class);
        List<TaskResultRecord> results =
                journalService.readAll(tenantId, projectId, runId, TaskResultRecord.class);
        Map<String, TaskResultRecord> lastResultByState = new LinkedHashMap<>();
        for (TaskResultRecord r : results) {
            if (r.getState() != null) lastResultByState.put(r.getState(), r);
        }
        List<RunStepDto> steps = new ArrayList<>(entered.size());
        for (StateEnteredRecord e : entered) {
            TaskResultRecord result = lastResultByState.get(e.getState());
            steps.add(RunStepDto.builder()
                    .name(e.getState())
                    .kind("state")
                    .outcome(result == null ? null : result.getOutcome())
                    .detail(result == null ? null : result.getErrorMessage())
                    .build());
        }
        return steps;
    }

    /** Sub-runs, found through the parent pointer the start record carries. */
    private List<RunChildDto> readChildren(String tenantId, String projectId, String runId) {
        List<RunChildDto> children = new ArrayList<>();
        for (String candidate : journalService.listRunIds(tenantId, projectId, 200)) {
            if (candidate.equals(runId)) continue;
            Optional<StartRecord> start = journalService.readLast(
                    tenantId, projectId, candidate, StartRecord.class);
            if (start.isEmpty()) continue;
            if (!runId.equals(start.get().getParentMagratheaProcessId())) continue;
            children.add(RunChildDto.builder()
                    .runId(RunId.of(SOURCE_ID, candidate).composite())
                    .name(start.get().getWorkflowName())
                    .fromStep(start.get().getParentState())
                    .build());
        }
        return children;
    }

    /** Link back to the definition — the frozen snapshot's source, when it had one. */
    private List<RunLinkDto> readLinks(
            String tenantId, String projectId, String runId, MagratheaProcessDto run) {
        Optional<StartRecord> start = journalService.readLast(
                tenantId, projectId, runId, StartRecord.class);
        String path = start.map(StartRecord::getSourcePath).orElse(null);
        if (path == null && run.getWorkflowName() != null) {
            path = MagratheaWorkflowLoader.WORKFLOW_PATH_PREFIX
                    + run.getWorkflowName() + MagratheaWorkflowLoader.WORKFLOW_PATH_SUFFIX;
        }
        if (path == null) return List.of();
        return List.of(RunLinkDto.builder()
                .rel("definition").label(path).target(path).build());
    }
}
