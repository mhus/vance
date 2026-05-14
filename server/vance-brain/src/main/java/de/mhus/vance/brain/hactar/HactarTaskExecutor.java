package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.shared.hactar.HactarJournalService;
import de.mhus.vance.shared.hactar.HactarStateProjector;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.HactarTaskDocument;
import de.mhus.vance.shared.hactar.HactarWorkflowLoader;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import de.mhus.vance.shared.hactar.journal.StartRecord;
import de.mhus.vance.shared.hactar.journal.TaskStartedRecord;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Type-dispatcher. Indexed by {@link HactarTaskType}, it looks up the
 * matching {@link HactarTypeExecutor} bean, writes the
 * {@code TaskStartedRecord}, runs the executor, and (for synchronous
 * executors) publishes the {@link TaskCompletedEvent} so the
 * {@code HactarWorkflowService} listener can flush the journal and
 * enqueue the next task (plan §4.0).
 *
 * <p>Always invoked from a {@link HactarProjectLane} — never directly
 * from a Spring scheduler or a web thread.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@Slf4j
public class HactarTaskExecutor {

    private final HactarJournalService journalService;
    private final HactarStateProjector projector;
    private final HactarWorkflowLoader workflowLoader;
    private final HactarCompletionEventBus eventBus;
    private final Map<HactarTaskType, HactarTypeExecutor> byType;

    public HactarTaskExecutor(
            HactarJournalService journalService,
            HactarStateProjector projector,
            HactarWorkflowLoader workflowLoader,
            HactarCompletionEventBus eventBus,
            List<HactarTypeExecutor> executors) {
        this.journalService = journalService;
        this.projector = projector;
        this.workflowLoader = workflowLoader;
        this.eventBus = eventBus;
        this.byType = new EnumMap<>(HactarTaskType.class);
        for (HactarTypeExecutor executor : executors) {
            HactarTypeExecutor previous = byType.put(executor.type(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate HactarTypeExecutor for " + executor.type()
                                + ": " + previous.getClass().getName() + " vs "
                                + executor.getClass().getName());
            }
        }
    }

    /**
     * Run a single claimed task. Caller is the project lane; the lane
     * guarantees no concurrent invocations for the same project.
     */
    public void execute(HactarTaskDocument task) {
        Optional<StartRecord> maybeStart = journalService.readLast(
                task.getWorkflowRunId(), StartRecord.class);
        if (maybeStart.isEmpty()) {
            log.error("Hactar task {} references run {} without StartRecord — failing",
                    task.getId(), task.getWorkflowRunId());
            publishFailure(task, "missing StartRecord");
            return;
        }
        StartRecord start = maybeStart.get();

        ResolvedHactarWorkflow workflow;
        try {
            workflow = workflowLoader.validateYaml(start.getWorkflowName(), start.getDefinitionYaml());
        } catch (RuntimeException ex) {
            log.error("Hactar task {} cannot re-parse frozen YAML for run {}",
                    task.getId(), task.getWorkflowRunId(), ex);
            publishFailure(task, "frozen YAML invalid: " + ex.getMessage());
            return;
        }

        HactarStateSpec state = workflow.states().get(task.getStateName());
        if (state == null) {
            log.error("Hactar task {} references state '{}' which is not in the frozen workflow",
                    task.getId(), task.getStateName());
            publishFailure(task, "state not in workflow: " + task.getStateName());
            return;
        }

        HactarTypeExecutor executor = byType.get(state.type());
        if (executor == null) {
            log.error("Hactar task {} has type {} but no executor is registered",
                    task.getId(), state.type());
            publishFailure(task, "no executor for type " + state.type());
            return;
        }

        // Replay vars + params for the executor's view.
        Map<String, Object> vars = projector.projectVars(task.getWorkflowRunId());
        Map<String, Object> params = start.getParams() == null ? Map.of() : start.getParams();

        // TaskStartedRecord — marks the execution window opened.
        journalService.append(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                TaskStartedRecord.builder()
                        .state(task.getStateName())
                        .taskType(state.type())
                        .taskId(task.getId())
                        .claimedBy(task.getClaimedBy())
                        .subProcessId(task.getSubProcessId())
                        .subWorkflowRunId(task.getSubWorkflowRunId())
                        .build());

        HactarTaskContext ctx = new HactarTaskContext(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                start.getStartedBy(),
                workflow,
                state,
                params,
                vars);

        long started = System.currentTimeMillis();
        Optional<TaskOutcome> outcome;
        try {
            outcome = executor.execute(ctx);
        } catch (RuntimeException ex) {
            log.error("Hactar type-executor {} threw on task {}",
                    executor.getClass().getSimpleName(), task.getId(), ex);
            publishFailure(task, ex.getMessage());
            return;
        }

        if (outcome.isEmpty()) {
            log.debug("Hactar task {} ({}) is async — waiting for listener",
                    task.getId(), state.type());
            return;
        }
        publishOutcome(task, state.type(), outcome.get(), System.currentTimeMillis() - started);
    }

    private void publishFailure(HactarTaskDocument task, String message) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                task.getTaskType(),
                "technical_error",
                null,
                message,
                0L,
                null));
    }

    private void publishOutcome(
            HactarTaskDocument task,
            HactarTaskType stateType,
            TaskOutcome outcome,
            long durationMs) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                stateType,
                outcome.outcome(),
                outcome.output(),
                outcome.errorMessage(),
                durationMs,
                outcome.nextStateOverride()));
    }

}
