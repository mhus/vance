package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunChildDto;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunLinkDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan-shaped ThinkProcesses as runs — Vogon phases, Marvin task trees.
 *
 * <p>Which engines qualify is not decided here: each engine answers
 * {@link ThinkEngine#planShaped()} for itself. A name list in this class
 * would be one more thing to forget when an engine is added, and it would
 * put the question in the wrong place — whether there is a plan to show
 * is a property of the engine, not of the view.
 *
 * <p>Progress comes from {@link StrategyState} in the process's engine
 * params, which is where Vogon keeps it. Reading it here rather than
 * teaching the insights DTO about it keeps the coupling one-way: the run
 * view knows about strategies, the strategy engine knows nothing about
 * the run view.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThinkProcessRunSource implements RunSource {

    public static final String SOURCE_ID = "process";

    /** Where Vogon parks its {@link StrategyState} inside engine params. */
    private static final String STRATEGY_STATE_KEY = "strategyState";

    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ObjectMapper objectMapper;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RunSummaryDto> list(String tenantId, String projectId, int limit) {
        List<RunSummaryDto> out = new ArrayList<>();
        // Over-fetch: the engine filter throws most of a project's
        // processes away (every chat turn is one), so a plain limit would
        // return a near-empty list on a busy project.
        for (ThinkProcessDocument p : thinkProcessService.findByProject(tenantId, projectId, limit * 10)) {
            if (!isPlanShaped(p)) continue;
            out.add(toSummary(p));
            if (out.size() >= limit) break;
        }
        return out;
    }

    @Override
    public Optional<RunDetailDto> get(String tenantId, String projectId, String nativeId) {
        Optional<ThinkProcessDocument> found = thinkProcessService.findById(nativeId);
        if (found.isEmpty()) return Optional.empty();
        ThinkProcessDocument process = found.get();
        if (!tenantId.equals(process.getTenantId())
                || !projectId.equals(process.getProjectId())) {
            return Optional.empty();
        }

        StrategyState state = readStrategyState(process);
        return Optional.of(RunDetailDto.builder()
                .summary(toSummary(process))
                .steps(readSteps(state))
                .variables(state == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.getFlags()))
                .children(readChildren(state))
                .links(List.of(RunLinkDto.builder()
                        .rel("session").label(process.getSessionId())
                        .target(process.getSessionId()).build()))
                .waitingOnInboxItemId(state == null || state.getPendingCheckpoint() == null
                        ? null : state.getPendingCheckpoint().getInboxItemId())
                .extra(Map.of(
                        "engine", process.getThinkEngine(),
                        "recipe", process.getRecipeName() == null ? "" : process.getRecipeName(),
                        "goal", process.getGoal() == null ? "" : process.getGoal()))
                .build());
    }

    private boolean isPlanShaped(ThinkProcessDocument process) {
        return thinkEngineService.resolve(process.getThinkEngine())
                .map(ThinkEngine::planShaped)
                .orElse(false);
    }

    private RunSummaryDto toSummary(ThinkProcessDocument process) {
        StrategyState state = readStrategyState(process);
        return RunSummaryDto.builder()
                .runId(RunId.of(SOURCE_ID, process.getId()).composite())
                .source(SOURCE_ID)
                .name(process.getTitle() != null && !process.getTitle().isBlank()
                        ? process.getTitle() : process.getName())
                .status(mapStatus(process.getStatus(), process.getCloseReason()))
                .step(currentPhase(state))
                .projectId(process.getProjectId())
                .startedBy(process.getRecipeName())
                .startedAt(process.getCreatedAt())
                .updatedAt(process.getUpdatedAt())
                .parentRunId(process.getParentProcessId() == null
                        ? null : RunId.of(SOURCE_ID, process.getParentProcessId()).composite())
                .build();
    }

    /**
     * Seven statuses and eight close reasons onto six. The lossy part is
     * intentional: {@code IDLE} and {@code BLOCKED} both mean "waiting for
     * something outside", and {@code SUSPENDED} is a hold like a pause even
     * though its owner is the session rather than the user.
     */
    private static RunStatus mapStatus(
            @Nullable ThinkProcessStatus status, @Nullable CloseReason closeReason) {
        if (status == null) return RunStatus.RUNNING;
        return switch (status) {
            case INIT, RUNNING -> RunStatus.RUNNING;
            case IDLE, BLOCKED -> RunStatus.WAITING;
            case PAUSED, SUSPENDED -> RunStatus.PAUSED;
            case CLOSED -> mapCloseReason(closeReason);
        };
    }

    private static RunStatus mapCloseReason(@Nullable CloseReason reason) {
        if (reason == null) return RunStatus.FAILED;
        return switch (reason) {
            case DONE, AUTO_CLOSE -> RunStatus.DONE;
            case INCOMPLETE, STALE -> RunStatus.FAILED;
            case STOPPED, ARCHIVED, USER_DELETE, ABANDONED -> RunStatus.STOPPED;
        };
    }

    private static @Nullable String currentPhase(@Nullable StrategyState state) {
        if (state == null) return null;
        List<String> path = state.getCurrentPhasePath();
        return path == null || path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /** Finished phases from the history, then the one currently open. */
    private static List<RunStepDto> readSteps(@Nullable StrategyState state) {
        if (state == null) return List.of();
        List<RunStepDto> steps = new ArrayList<>();
        for (String phase : state.getPhaseHistory()) {
            steps.add(RunStepDto.builder().name(phase).kind("phase").outcome("done").build());
        }
        String current = currentPhase(state);
        if (current != null && !state.getPhaseHistory().contains(current)) {
            steps.add(RunStepDto.builder().name(current).kind("phase").build());
        }
        return steps;
    }

    private static List<RunChildDto> readChildren(@Nullable StrategyState state) {
        if (state == null) return List.of();
        List<RunChildDto> children = new ArrayList<>();
        for (Map.Entry<String, String> e : state.getWorkerProcessIds().entrySet()) {
            children.add(RunChildDto.builder()
                    .runId(RunId.of(SOURCE_ID, e.getValue()).composite())
                    .name(e.getValue())
                    .fromStep(e.getKey())
                    .build());
        }
        return children;
    }

    private @Nullable StrategyState readStrategyState(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return null;
        Object raw = params.get(STRATEGY_STATE_KEY);
        if (raw == null) return null;
        try {
            return objectMapper.convertValue(raw, StrategyState.class);
        } catch (RuntimeException ex) {
            log.debug("Run view: process '{}' has an unreadable strategyState: {}",
                    process.getId(), ex.toString());
            return null;
        }
    }
}
