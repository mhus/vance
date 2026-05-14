package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarRunStatus;
import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarTaskDocument;
import de.mhus.vance.shared.hactar.HactarTaskService;
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
 *   <li>Sub-run reached {@link HactarRunStatus#DONE} → parent
 *       outcome {@code success}, parent output =
 *       {@link WorkflowCompletedEvent#result()}.</li>
 *   <li>{@link HactarRunStatus#FAILED} / {@link HactarRunStatus#TERMINATED}
 *       → parent outcome {@code failure}.</li>
 *   <li>{@link HactarRunStatus#PAUSED} or anything else → ignored —
 *       only terminal statuses advance the parent.</li>
 * </ul>
 *
 * <p>Top-level runs (no {@code parentHactarProcessId} on
 * {@code StartRecord}) leave the listener silent — the event still
 * fires, but no parent task is linked.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class HactarSubWorkflowCompletionListener {

    private final HactarTaskService taskService;
    private final HactarCompletionEventBus eventBus;

    @EventListener
    public void onCompleted(WorkflowCompletedEvent event) {
        if (event.parentHactarProcessId() == null) {
            return;
        }
        Optional<HactarTaskDocument> taskOpt = taskService.findBySubWorkflowRunId(
                event.workflowRunId());
        if (taskOpt.isEmpty()) {
            log.warn("Hactar sub-workflow listener: run '{}' has a parent in its start record "
                    + "but no parent task with this subWorkflowRunId",
                    event.workflowRunId());
            return;
        }
        HactarTaskDocument task = taskOpt.get();
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
                HactarTaskType.WORKFLOW_TASK,
                outcome,
                event.result(),
                errorMessage,
                0L,
                null));
        log.info("Hactar workflow_task completion task='{}' sub='{}' outcome={}",
                task.getId(), event.workflowRunId(), outcome);
    }
}
