package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Focused tests for the W11 paths inside
 * {@link MagratheaWorkflowService#handleCompletion}: state-level retry on
 * matching error-kind and bounds enforcement (maxWallclockSeconds,
 * maxTaskSpawns).
 */
class MagratheaWorkflowServiceRetryAndBoundsTest {

    private static final String YAML_WITH_RETRY = """
            start: flaky
            states:
              flaky:
                type: shell_task
                retry:
                  maxAttempts: 3
                  on: [technical_error]
                  backoffSeconds: 0
                on:
                  success: done
                catch:
                  technical_error: escalated
              done:
                type: terminal
                outcome: success
              escalated:
                type: terminal
                outcome: failure
            """;

    private static final String YAML_WITH_BOUNDS = """
            start: loop
            bounds:
              maxTaskSpawns: 2
            states:
              loop:
                type: condition_task
                transitions:
                  - else: loop
            """;

    private final MagratheaJournalService journalService = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaTaskExecutor taskExecutor = mock(MagratheaTaskExecutor.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final de.mhus.vance.shared.metric.MetricService metricService =
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final MagratheaWorkflowService workflowService = new MagratheaWorkflowService(
            workflowLoader, journalService, taskService, laneManager, taskExecutor,
            eventPublisher, metricService);

    private final List<MagratheaTaskDocument> insertedTasks = new ArrayList<>();
    private final List<JournalRecord> appendedRecords = new ArrayList<>();

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(laneManager).submit(any(), any());
        when(laneManager.submitTracked(any(), any())).thenAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        when(taskService.insert(any())).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            if (t.getId() == null) t.setId("t" + (insertedTasks.size() + 1));
            insertedTasks.add(t);
            return t;
        });
        when(taskService.findById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return insertedTasks.stream().filter(t -> id.equals(t.getId())).findFirst();
        });
        when(journalService.append(any(), any(), any(), any(JournalRecord.class)))
                .thenAnswer(inv -> {
                    appendedRecords.add(inv.getArgument(3));
                    return MagratheaJournalEntry.builder().build();
                });
        when(journalService.appendIfAbsent(any(), any(), any(), any(), any(JournalRecord.class)))
                .thenAnswer(inv -> {
                    appendedRecords.add(inv.getArgument(4));
                    return Optional.of(MagratheaJournalEntry.builder().build());
                });

        MagratheaWorkflowLoader realLoader = new MagratheaWorkflowLoader(null);
        when(workflowLoader.load(any(), any(), eq("flaky-wf")))
                .thenAnswer(inv -> Optional.of(realLoader.validateYaml("flaky-wf", YAML_WITH_RETRY)));
        when(workflowLoader.validateYaml(eq("flaky-wf"), any()))
                .thenAnswer(inv -> realLoader.validateYaml("flaky-wf", YAML_WITH_RETRY));
        when(workflowLoader.load(any(), any(), eq("bounded-wf")))
                .thenAnswer(inv -> Optional.of(realLoader.validateYaml("bounded-wf", YAML_WITH_BOUNDS)));
        when(workflowLoader.validateYaml(eq("bounded-wf"), any()))
                .thenAnswer(inv -> realLoader.validateYaml("bounded-wf", YAML_WITH_BOUNDS));
    }

    @Test
    void technical_error_with_retry_room_enqueues_a_retry_in_same_state() {
        StartRecord start = StartRecord.builder()
                .workflowName("flaky-wf").definitionYaml(YAML_WITH_RETRY).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        String runId = workflowService.start("acme", "proj", "flaky-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "flaky",
                MagratheaTaskType.SHELL_TASK, "technical_error",
                null, "exec failed", 5L, null);

        workflowService.handleCompletion(event);

        assertThat(insertedTasks).hasSize(2);
        MagratheaTaskDocument retry = insertedTasks.get(1);
        assertThat(retry.getStateName()).isEqualTo("flaky");
        assertThat(retry.getRetryCount()).isEqualTo(1);
        verify(taskService).markFailed(firstTask.getId());
    }

    @Test
    void technical_error_after_max_attempts_falls_through_to_catch_block() {
        StartRecord start = StartRecord.builder()
                .workflowName("flaky-wf").definitionYaml(YAML_WITH_RETRY).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        String runId = workflowService.start("acme", "proj", "flaky-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        // Force the task to look like it's already at the retry limit.
        firstTask.setRetryCount(2);  // next retry would be 3 == maxAttempts → fall through
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "flaky",
                MagratheaTaskType.SHELL_TASK, "technical_error",
                null, "exec failed", 5L, null);

        workflowService.handleCompletion(event);

        // No retry, instead the catch-block routes to 'escalated'.
        assertThat(insertedTasks).hasSize(2);
        MagratheaTaskDocument next = insertedTasks.get(1);
        assertThat(next.getStateName()).isEqualTo("escalated");
        assertThat(next.getRetryCount()).isEqualTo(0);
    }

    @Test
    void business_error_not_in_retry_on_list_skips_retry_path() {
        StartRecord start = StartRecord.builder()
                .workflowName("flaky-wf").definitionYaml(YAML_WITH_RETRY).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        String runId = workflowService.start("acme", "proj", "flaky-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "flaky",
                MagratheaTaskType.SHELL_TASK, "business_error",
                null, "exit 1", 5L, null);

        workflowService.handleCompletion(event);

        // business_error isn't in retry.on, isn't in catch — falls through
        // to "no transition" → run FAILED.
        verify(taskService).markFailed(firstTask.getId());
        // No retry task, only a StatusRecord(FAILED) for the run failure.
        assertThat(insertedTasks).hasSize(1);
        assertThat(appendedRecords).hasAtLeastOneElementOfType(StatusRecord.class);
    }

    @Test
    void successful_outcome_takes_the_on_block_without_retry() {
        StartRecord start = StartRecord.builder()
                .workflowName("flaky-wf").definitionYaml(YAML_WITH_RETRY).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        String runId = workflowService.start("acme", "proj", "flaky-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "flaky",
                MagratheaTaskType.SHELL_TASK, TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 5L, null);

        workflowService.handleCompletion(event);

        assertThat(insertedTasks).hasSize(2);
        assertThat(insertedTasks.get(1).getStateName()).isEqualTo("done");
        verify(taskService).markDone(firstTask.getId());
    }

    @Test
    void maxTaskSpawns_exhausted_fails_the_run_with_bounds_reason() {
        StartRecord start = StartRecord.builder()
                .workflowName("bounded-wf").definitionYaml(YAML_WITH_BOUNDS).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        // 3 TaskStartedRecords already → bounds.maxTaskSpawns=2 exceeded.
        when(journalService.count(any(), eq(TaskStartedRecord.class))).thenReturn(3L);

        String runId = workflowService.start("acme", "proj", "bounded-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "loop",
                MagratheaTaskType.CONDITION_TASK, TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 1L, "loop");

        workflowService.handleCompletion(event);

        // No new task: bounds bailed out before transition resolved.
        assertThat(insertedTasks).hasSize(1);
        StatusRecord status = (StatusRecord) appendedRecords.stream()
                .filter(r -> r instanceof StatusRecord).reduce((a, b) -> b).orElseThrow();
        assertThat(status.getReason()).contains("bounds exhausted").contains("maxTaskSpawns");
    }

    @Test
    void maxWallclockSeconds_exhausted_fails_the_run() {
        StartRecord start = StartRecord.builder()
                .workflowName("bounded-wf").definitionYaml(
                        // Override to wallclock-only bounds.
                        """
                        start: loop
                        bounds:
                          maxWallclockSeconds: 1
                        states:
                          loop:
                            type: condition_task
                            transitions:
                              - else: loop
                        """).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        // Force the run to look 60s old.
        when(journalService.firstCreatedAt(any()))
                .thenReturn(Optional.of(Instant.now().minusSeconds(60)));
        // Hook the loader's validateYaml for the override.
        when(workflowLoader.validateYaml(any(), any()))
                .thenAnswer(inv -> new MagratheaWorkflowLoader(null)
                        .validateYaml(inv.getArgument(0), inv.getArgument(1)));

        String runId = workflowService.start("acme", "proj", "bounded-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "loop",
                MagratheaTaskType.CONDITION_TASK, TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 1L, "loop");

        workflowService.handleCompletion(event);

        StatusRecord status = (StatusRecord) appendedRecords.stream()
                .filter(r -> r instanceof StatusRecord).reduce((a, b) -> b).orElseThrow();
        assertThat(status.getReason()).contains("maxWallclockSeconds");
    }

    @Test
    void within_bounds_workflow_proceeds_normally() {
        StartRecord start = StartRecord.builder()
                .workflowName("bounded-wf").definitionYaml(YAML_WITH_BOUNDS).build();
        when(journalService.readLast(any(), eq(StartRecord.class))).thenReturn(Optional.of(start));
        when(journalService.count(any(), eq(TaskStartedRecord.class))).thenReturn(1L);

        String runId = workflowService.start("acme", "proj", "bounded-wf", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "loop",
                MagratheaTaskType.CONDITION_TASK, TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 1L, "loop");

        workflowService.handleCompletion(event);

        // Loop continues normally.
        assertThat(insertedTasks).hasSize(2);
        verify(taskService, times(1)).markDone(firstTask.getId());
    }
}
