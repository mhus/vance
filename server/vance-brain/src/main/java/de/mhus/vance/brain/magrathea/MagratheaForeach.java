package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * For-each over the items of a {@code workflow_task}: one sub-run per element, one after
 * the other. Fan-out stays in the workflow — each element is a run of its own, with its own
 * journal, listed under the parent in the run view and debuggable like any run.
 *
 * <p>The executor starts the first element ({@link #begin}); when a sub-run ends, the
 * completion listener hands it back ({@link #onElementDone}), which starts the next one or
 * completes the task with the list of results. What the loop needs between two elements —
 * the substituted items and parameters, the index, the results so far — is kept on the task
 * row as JSON ({@link MagratheaTaskDocument#getForeachState()}), so nothing depends on a
 * pod staying up.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@Slf4j
public class MagratheaForeach {

    /** Elements processed when the state does not say. */
    static final int DEFAULT_MAX_ITEMS = 100;

    /** The most elements any for-each may have, whatever the state says. */
    static final int HARD_MAX_ITEMS = 1000;

    /**
     * What the loop keeps between elements.
     *
     * @param workflow        the sub-workflow run for each element
     * @param as              the parameter name the element is passed under
     * @param continueOnError {@code true}: a failed element is recorded and the loop goes on;
     *                        {@code false}: the first failed element ends the task
     * @param startedBy       identity the sub-runs are started under
     * @param items           the elements, as substituted when the task started
     * @param params          the shared parameters of every element
     * @param index           the element whose sub-run is running (from 0)
     * @param results         results of the elements before {@code index}
     */
    public record State(
            String workflow,
            String as,
            boolean continueOnError,
            @Nullable String startedBy,
            List<Object> items,
            Map<String, Object> params,
            int index,
            List<Object> results) {}

    private final MagratheaWorkflowService workflowService;
    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;
    private final ObjectMapper objectMapper;

    public MagratheaForeach(
            @Lazy MagratheaWorkflowService workflowService,
            MagratheaTaskService taskService,
            MagratheaCompletionEventBus eventBus,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.taskService = taskService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    /**
     * Start the loop: check the items and start the sub-run of the first element.
     *
     * @return an outcome when the loop is over before it began (not a list, too many,
     *         empty, the first element would not start); empty when the first element is
     *         running and the task waits for it
     */
    Optional<TaskOutcome> begin(
            MagratheaTaskContext context,
            String workflow,
            Map<String, Object> params,
            @Nullable Object rawItems,
            String as,
            boolean continueOnError,
            int maxItems) {
        if (!(rawItems instanceof List<?> items)) {
            return Optional.of(TaskOutcome.failure(
                    "workflow_task '" + context.state().name() + "': items must be a list, got " + describe(rawItems)));
        }
        if (items.size() > maxItems) {
            return Optional.of(TaskOutcome.failure("workflow_task '"
                    + context.state().name() + "': " + items.size() + " items, more than maxItems " + maxItems));
        }
        if (items.isEmpty()) {
            return Optional.of(TaskOutcome.successWith(objectMapper.createArrayNode()));
        }
        State state = new State(
                workflow,
                as,
                continueOnError,
                context.startedBy(),
                new ArrayList<>(items),
                new LinkedHashMap<>(params),
                0,
                new ArrayList<>());
        String subRunId;
        try {
            // The progress is written before the sub-run exists: the run's end must find it.
            taskService.setForeachState(context.taskId(), write(state));
            subRunId = startElement(
                    context.tenantId(),
                    context.projectId(),
                    context.workflowRunId(),
                    context.state().name(),
                    state);
        } catch (RuntimeException ex) {
            log.warn(
                    "Magrathea for-each '{}' could not start its first element: {}",
                    context.state().name(),
                    ex.getMessage());
            return Optional.of(TaskOutcome.failure("First element could not be started: " + ex.getMessage()));
        }
        taskService.linkSubWorkflow(context.taskId(), subRunId);
        log.info(
                "Magrathea for-each '{}' started: {} item(s), first sub-run {}",
                context.state().name(),
                items.size(),
                subRunId);
        return Optional.empty();
    }

    /**
     * An element's sub-run has ended: record its result and start the next element, or end
     * the task.
     *
     * @param outcome      {@code success} or {@code failure}, as the listener classified the sub-run
     * @param errorMessage why it failed, for {@code failure}
     * @return {@code true} when the task is a for-each and was dealt with here; {@code false}
     *         for an ordinary sub-workflow task, which the listener completes itself
     */
    boolean onElementDone(
            MagratheaTaskDocument task, String outcome, @Nullable JsonNode result, @Nullable String errorMessage) {
        if (task.getForeachState() == null) return false;
        State state = read(task.getForeachState());

        // A stop (or the end) of the parent run ends the task from outside: nothing may start.
        MagratheaRunStatus parent =
                workflowService.currentStatus(task.getTenantId(), task.getProjectId(), task.getWorkflowRunId());
        if (parent == MagratheaRunStatus.DONE
                || parent == MagratheaRunStatus.FAILED
                || parent == MagratheaRunStatus.TERMINATED) {
            log.info(
                    "Magrathea for-each task {}: parent run is {} — no further element is started",
                    task.getId(),
                    parent);
            return true;
        }

        boolean ok = TaskCompletedEvent.OUTCOME_SUCCESS.equals(outcome);
        List<Object> results = new ArrayList<>(state.results());
        results.add(
                ok
                        ? (result == null ? null : objectMapper.convertValue(result, Object.class))
                        : failedEntry(errorMessage));

        if (!ok && !state.continueOnError()) {
            complete(
                    task,
                    TaskCompletedEvent.OUTCOME_FAILURE,
                    results,
                    "Element " + state.index() + " failed: " + errorMessage);
            return true;
        }
        int next = state.index() + 1;
        if (next >= state.items().size()) {
            complete(task, TaskCompletedEvent.OUTCOME_SUCCESS, results, null);
            return true;
        }

        State advanced = new State(
                state.workflow(),
                state.as(),
                state.continueOnError(),
                state.startedBy(),
                state.items(),
                state.params(),
                next,
                results);
        try {
            taskService.setForeachState(task.getId(), write(advanced));
            String subRunId = startElement(
                    task.getTenantId(), task.getProjectId(), task.getWorkflowRunId(), task.getStateName(), advanced);
            taskService.linkSubWorkflow(task.getId(), subRunId);
        } catch (RuntimeException ex) {
            log.warn("Magrathea for-each task {} could not start element {}: {}", task.getId(), next, ex.getMessage());
            complete(
                    task,
                    TaskCompletedEvent.OUTCOME_FAILURE,
                    results,
                    "Element " + next + " could not be started: " + ex.getMessage());
        }
        return true;
    }

    private String startElement(String tenantId, String projectId, String runId, String stateName, State state) {
        Map<String, Object> params = new LinkedHashMap<>(state.params());
        params.put(state.as(), state.items().get(state.index()));
        params.put("index", state.index());
        params.put("count", state.items().size());
        return workflowService.start(
                tenantId,
                projectId,
                state.workflow(),
                params,
                state.startedBy(),
                /* parentMagratheaProcessId */ runId, /* parentState */
                stateName);
    }

    private void complete(MagratheaTaskDocument task, String outcome, List<Object> results, @Nullable String error) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                MagratheaTaskType.WORKFLOW_TASK,
                outcome,
                // What there is so far is useful on a failure too: storeAs keeps it.
                objectMapper.valueToTree(results),
                error,
                MagratheaTiming.sinceClaimMs(task),
                null));
        log.info("Magrathea for-each task {} ended '{}' after {} result(s)", task.getId(), outcome, results.size());
    }

    private static Map<String, Object> failedEntry(@Nullable String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("failed", true);
        entry.put("message", message == null ? "" : message);
        return entry;
    }

    private static String describe(@Nullable Object value) {
        if (value == null) return "nothing";
        if (value instanceof String s) return s.isEmpty() ? "nothing (the reference resolved to nothing)" : "text";
        return value.getClass().getSimpleName();
    }

    private String write(State state) {
        return objectMapper.writeValueAsString(state);
    }

    private State read(String json) {
        return objectMapper.readValue(json, State.class);
    }
}
