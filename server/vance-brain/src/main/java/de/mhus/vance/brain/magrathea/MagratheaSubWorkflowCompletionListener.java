package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@link WorkflowCompletedEvent} and surfaces sub-workflow
 * terminations to the {@code workflow_task} waiting on each (plan §4.7,
 * §6.4).
 *
 * <h3>Outcome mapping</h3>
 * <ul>
 *   <li>Sub-run reached {@link MagratheaRunStatus#DONE} → parent
 *       outcome {@code success}, parent output =
 *       {@link WorkflowCompletedEvent#result()}.</li>
 *   <li>{@link MagratheaRunStatus#FAILED} / {@link MagratheaRunStatus#TERMINATED}
 *       → parent outcome {@code failure}.</li>
 *   <li>{@link MagratheaRunStatus#PAUSED} or anything else → ignored —
 *       only terminal statuses advance the parent.</li>
 * </ul>
 *
 * <p>Top-level runs (no {@code parentMagratheaProcessId} on
 * {@code StartRecord}) leave the listener silent — the event still
 * fires, but no parent task is linked.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaSubWorkflowCompletionListener {

    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;

    @EventListener
    public void onCompleted(WorkflowCompletedEvent event) {
        if (event.parentMagratheaProcessId() == null) {
            return;
        }
        Optional<MagratheaTaskDocument> taskOpt = taskService.findBySubWorkflowRunId(
                event.workflowRunId());
        if (taskOpt.isEmpty()) {
            log.warn("Magrathea sub-workflow listener: run '{}' has a parent in its start record "
                    + "but no parent task with this subWorkflowRunId",
                    event.workflowRunId());
            return;
        }
        MagratheaTaskDocument task = taskOpt.get();
        String outcome;
        String errorMessage = null;
        switch (event.status()) {
            case DONE:
                outcome = TaskCompletedEvent.OUTCOME_SUCCESS;
                break;
            case FAILED:
            case TERMINATED:
                outcome = TaskCompletedEvent.OUTCOME_FAILURE;
                errorMessage = "Sub-workflow '" + event.workflowName()
                        + "' ended in " + event.status();
                break;
            default:
                // PAUSED / RUNNING → not terminal; ignore.
                return;
        }
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                MagratheaTaskType.WORKFLOW_TASK,
                outcome,
                event.result(),
                errorMessage,
                0L,
                null));
        log.info("Magrathea workflow_task completion task='{}' sub='{}' outcome={}",
                task.getId(), event.workflowRunId(), outcome);
    }
}
