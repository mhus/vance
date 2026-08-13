package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.damogran.ComposeRun;
import de.mhus.vance.brain.damogran.ComposeRunRegistry;
import de.mhus.vance.brain.damogran.DamogranTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Damogran compose runs as {@link RunSource}.
 *
 * <p><b>This source is a live window, not a history.</b> The registry
 * behind it is an in-memory map: pod-local, capped, and terminal runs
 * swept ten minutes after they finish. A compose run that ran yesterday
 * is simply not here, and on a second pod neither is one that ran a
 * minute ago.
 *
 * <p>That still covers the case the run view exists for — "I started
 * something, where is it" — which is why it ships rather than waiting for
 * persistence. The UI labels the limit; a run that vanishes must not read
 * as a bug ({@code planning/runs-view.md} §6.1).
 */
@Component
@RequiredArgsConstructor
public class ComposeRunSource implements RunSource {

    public static final String SOURCE_ID = "compose";

    private final ComposeRunRegistry registry;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RunSummaryDto> list(String tenantId, String projectId, int limit) {
        return registry.list(tenantId, projectId, limit).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public Optional<RunDetailDto> get(String tenantId, String projectId, String nativeId) {
        return registry.find(tenantId, projectId, nativeId).map(run -> RunDetailDto.builder()
                .summary(toSummary(run))
                .steps(readSteps(run))
                .errorMessage(run.error())
                .extra(Map.of(
                        "workspace", run.workspaceName(),
                        "transient", true))
                .build());
    }

    private RunSummaryDto toSummary(ComposeRun run) {
        return RunSummaryDto.builder()
                .runId(RunId.of(SOURCE_ID, run.runId()).composite())
                .source(SOURCE_ID)
                .name(run.workspaceName())
                .status(mapStatus(run))
                .step(run.currentTaskType())
                .projectId(run.projectId())
                .startedAt(run.startedAt())
                .updatedAt(run.finishedAt() == null ? run.startedAt() : run.finishedAt())
                .build();
    }

    private static RunStatus mapStatus(ComposeRun run) {
        if (!run.isTerminal()) {
            return run.isCancelRequested() ? RunStatus.STOPPING : RunStatus.RUNNING;
        }
        if (run.isCancelRequested()) return RunStatus.STOPPED;
        return run.error() == null ? RunStatus.DONE : RunStatus.FAILED;
    }

    /** One step per finished task, in execution order. */
    private static List<RunStepDto> readSteps(ComposeRun run) {
        List<RunStepDto> steps = new ArrayList<>();
        int index = 0;
        for (DamogranTaskResult task : run.doneTasks()) {
            steps.add(RunStepDto.builder()
                    .name("task " + (++index))
                    .kind("task")
                    .outcome(task.isSuccess() ? "success" : "failure")
                    .detail(task.error())
                    .build());
        }
        String current = run.currentTaskType();
        if (!run.isTerminal() && current != null) {
            steps.add(RunStepDto.builder()
                    .name("task " + (index + 1)).kind(current).build());
        }
        return steps;
    }
}
