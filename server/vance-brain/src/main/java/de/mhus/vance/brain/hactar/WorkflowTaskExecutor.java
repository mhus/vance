package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.HactarTaskService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Sub-workflow executor (plan §4.7). Spawns another workflow as a
 * child run, links its id to the parent task and returns async — the
 * completion arrives through {@link HactarSubWorkflowCompletionListener}
 * when the sub-run reaches a terminal status.
 *
 * <h3>YAML</h3>
 * <pre>
 * build_subprojects:
 *   type: workflow_task
 *   workflow: build-and-test         # name of another workflow in the cascade
 *   params:                           # forwarded to the sub-run as caller params
 *     repo_url: "${state.repo_url}"
 *     branch:   "${params.branch}"
 *   storeAs: build_result              # captures the sub-run's ResultRecord payload
 *   on:
 *     success: deploy
 *   catch:
 *     technical_error: escalate
 * </pre>
 *
 * <p>The parent's workflow run id and current state name are recorded
 * on the sub-run's {@code StartRecord}; the completion listener uses
 * them to advance the right parent task.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class WorkflowTaskExecutor implements HactarTypeExecutor {

    private static final String SPEC_WORKFLOW = "workflow";
    private static final String SPEC_PARAMS   = "params";

    private final HactarWorkflowService workflowService;
    private final HactarTaskService taskService;

    public WorkflowTaskExecutor(
            @Lazy HactarWorkflowService workflowService,
            HactarTaskService taskService) {
        this.workflowService = workflowService;
        this.taskService = taskService;
    }

    @Override
    public HactarTaskType type() {
        return HactarTaskType.WORKFLOW_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(HactarTaskContext context) {
        HactarStateSpec state = context.state();
        String subWorkflowName = state.specString(SPEC_WORKFLOW);
        if (subWorkflowName == null) {
            return Optional.of(TaskOutcome.failure(
                    "workflow_task '" + state.name() + "' is missing required 'workflow:' field"));
        }
        Map<String, Object> subParams = readParamsMap(state);

        String subRunId;
        try {
            subRunId = workflowService.start(
                    context.tenantId(),
                    context.projectId(),
                    subWorkflowName,
                    subParams,
                    context.startedBy(),
                    /* parentHactarProcessId */ context.workflowRunId(),
                    /* parentState */ state.name());
        } catch (HactarWorkflowService.HactarWorkflowException ex) {
            log.warn("Hactar workflow_task '{}' sub-spawn failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Sub-workflow start failed: " + ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Hactar workflow_task '{}' sub-spawn errored: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Sub-workflow start errored: " + ex.getMessage()));
        }

        taskService.linkSubWorkflow(context.taskId(), subRunId);
        log.info("Hactar workflow_task '{}' spawned sub-workflow '{}' runId={}",
                state.name(), subWorkflowName, subRunId);

        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParamsMap(HactarStateSpec state) {
        Object raw = state.specField(SPEC_PARAMS);
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(
                "workflow_task '" + state.name() + "' params must be a map");
    }
}
