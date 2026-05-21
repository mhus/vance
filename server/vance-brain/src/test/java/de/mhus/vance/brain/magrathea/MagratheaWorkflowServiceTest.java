package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration-style test for {@link MagratheaWorkflowService} with the
 * journal/task service mocked. Captures every journal write through
 * the mock so the test can assert which typed record landed and in
 * what order — without depending on the package-private repository.
 */
class MagratheaWorkflowServiceTest {

    private static final String YAML = """
            description: branch demo
            start: route
            states:
              route:
                type: condition_task
                transitions:
                  - if: "#state['risk'] == 'low'"
                    to: ok
                  - else: bad
              ok:
                type: terminal
                outcome: success
                result:
                  summary: looked good
              bad:
                type: terminal
                outcome: failure
            """;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MagratheaJournalService journalService = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaTaskExecutor taskExecutor = mock(MagratheaTaskExecutor.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final de.mhus.vance.shared.metric.MetricService metricService =
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final MagratheaWorkflowService workflowService = new MagratheaWorkflowService(
            workflowLoader, journalService, taskService, laneManager, taskExecutor,
            eventPublisher, metricService);

    private final List<JournalRecord> appendedRecords = new ArrayList<>();
    private final List<MagratheaTaskDocument> insertedTasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Synchronous lane.
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(laneManager).submit(any(), any());

        // journalService captures records.
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

        // taskService captures inserts and serves them back via findById.
        when(taskService.insert(any(MagratheaTaskDocument.class))).thenAnswer(inv -> {
            MagratheaTaskDocument t = inv.getArgument(0);
            if (t.getId() == null) t.setId("t" + (insertedTasks.size() + 1));
            insertedTasks.add(t);
            return t;
        });
        when(taskService.findById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return insertedTasks.stream().filter(t -> id.equals(t.getId())).findFirst();
        });

        // Real loader so we exercise the parser end-to-end. Scope the
        // stubs by workflow name so per-test additions (e.g. "withstore",
        // "req") never re-trigger this answer with null args during
        // their own when(...) setup.
        MagratheaWorkflowLoader realLoader = new MagratheaWorkflowLoader(null);
        when(workflowLoader.load(any(), any(), eq("demo")))
                .thenAnswer(inv -> Optional.of(realLoader.validateYaml("demo", YAML)));
        when(workflowLoader.validateYaml(eq("demo"), any()))
                .thenAnswer(inv -> realLoader.validateYaml("demo", YAML));
    }

    @Test
    void start_writes_StartRecord_StateEnteredRecord_and_PENDING_task() {
        String runId = workflowService.start("acme", "proj", "demo",
                Map.of("explicit", "value"), "alice");

        assertThat(runId).hasSize(8);
        assertThat(appendedRecords).hasSize(2);
        assertThat(appendedRecords.get(0)).isInstanceOf(StartRecord.class);
        assertThat(appendedRecords.get(1)).isInstanceOf(StateEnteredRecord.class);
        assertThat(((StateEnteredRecord) appendedRecords.get(1)).getState()).isEqualTo("route");
        StartRecord start = (StartRecord) appendedRecords.get(0);
        assertThat(start.getWorkflowName()).isEqualTo("demo");
        assertThat(start.getStartedBy()).isEqualTo("alice");
        assertThat(start.getParams()).containsEntry("explicit", "value");
        assertThat(insertedTasks).hasSize(1);
        assertThat(insertedTasks.get(0).getStateName()).isEqualTo("route");
        assertThat(insertedTasks.get(0).getTaskType()).isEqualTo(MagratheaTaskType.CONDITION_TASK);
        assertThat(insertedTasks.get(0).getStatus()).isEqualTo(MagratheaTaskStatus.PENDING);
    }

    @Test
    void start_throws_when_workflow_not_found() {
        when(workflowLoader.load(any(), any(), eq("missing"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.start("acme", "proj", "missing", Map.of(), null))
                .isInstanceOf(MagratheaWorkflowService.MagratheaWorkflowException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void condition_outcome_with_nextStateOverride_enqueues_next_task() {
        // Set up StartRecord lookup for handleCompletion.
        StartRecord start = StartRecord.builder()
                .workflowName("demo").definitionYaml(YAML).build();
        when(journalService.readLast(any(), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));

        String runId = workflowService.start("acme", "proj", "demo", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "route",
                MagratheaTaskType.CONDITION_TASK,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 5L, "ok");

        workflowService.handleCompletion(event);

        assertThat(appendedRecords).hasAtLeastOneElementOfType(TaskResultRecord.class);
        assertThat(appendedRecords).hasAtLeastOneElementOfType(StateEnteredRecord.class);
        verify(taskService).markDone(firstTask.getId());
        assertThat(insertedTasks).hasSize(2);
        assertThat(insertedTasks.get(1).getStateName()).isEqualTo("ok");
        assertThat(insertedTasks.get(1).getTaskType()).isEqualTo(MagratheaTaskType.TERMINAL);
    }

    @Test
    void terminal_success_writes_StatusRecord_DONE_and_ResultRecord() {
        StartRecord start = StartRecord.builder()
                .workflowName("demo").definitionYaml(YAML).build();
        when(journalService.readLast(any(), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));

        String runId = workflowService.start("acme", "proj", "demo", Map.of(), null);
        MagratheaTaskDocument okTask = MagratheaTaskDocument.builder()
                .id("t-ok").workflowRunId(runId).stateName("ok")
                .taskType(MagratheaTaskType.TERMINAL).status(MagratheaTaskStatus.CLAIMED).build();
        insertedTasks.add(okTask);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, "t-ok", "ok",
                MagratheaTaskType.TERMINAL,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                objectMapper.valueToTree(Map.of("summary", "merged")),
                null, 2L, null);

        workflowService.handleCompletion(event);

        StatusRecord status = (StatusRecord) appendedRecords.stream()
                .filter(r -> r instanceof StatusRecord)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(status.getStatus()).isEqualTo(de.mhus.vance.api.magrathea.MagratheaRunStatus.DONE);
        assertThat(appendedRecords).hasAtLeastOneElementOfType(
                de.mhus.vance.shared.magrathea.journal.ResultRecord.class);
        verify(taskService).markDone("t-ok");
        assertThat(insertedTasks).hasSize(2); // no follow-up
    }

    @Test
    void terminal_failure_writes_StatusRecord_FAILED() {
        StartRecord start = StartRecord.builder()
                .workflowName("demo").definitionYaml(YAML).build();
        when(journalService.readLast(any(), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));

        String runId = workflowService.start("acme", "proj", "demo", Map.of(), null);
        MagratheaTaskDocument badTask = MagratheaTaskDocument.builder()
                .id("t-bad").workflowRunId(runId).stateName("bad")
                .taskType(MagratheaTaskType.TERMINAL).status(MagratheaTaskStatus.CLAIMED).build();
        insertedTasks.add(badTask);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, "t-bad", "bad",
                MagratheaTaskType.TERMINAL,
                TaskCompletedEvent.OUTCOME_FAILURE,
                null, "terminal failure", 3L, null);

        workflowService.handleCompletion(event);

        StatusRecord status = (StatusRecord) appendedRecords.stream()
                .filter(r -> r instanceof StatusRecord)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(status.getStatus()).isEqualTo(de.mhus.vance.api.magrathea.MagratheaRunStatus.FAILED);
        verify(taskService).markFailed("t-bad");
    }

    @Test
    void duplicate_completion_event_short_circuits() {
        StartRecord start = StartRecord.builder()
                .workflowName("demo").definitionYaml(YAML).build();
        when(journalService.readLast(any(), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));

        String runId = workflowService.start("acme", "proj", "demo", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);

        // First completion advances normally.
        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "route",
                MagratheaTaskType.CONDITION_TASK,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                null, null, 5L, "ok");
        workflowService.handleCompletion(event);
        int after1st = insertedTasks.size();

        // appendIfAbsent now returns empty — simulating Mongo unique-index reject.
        when(journalService.appendIfAbsent(any(), any(), any(), any(), any(JournalRecord.class)))
                .thenReturn(Optional.empty());

        workflowService.handleCompletion(event);

        assertThat(insertedTasks).hasSize(after1st);
        verify(taskService, times(1)).markDone(firstTask.getId());
    }

    @Test
    void storeAs_writes_VarRecord_when_output_present() {
        String yamlWithStore = """
                start: cap
                states:
                  cap:
                    type: condition_task
                    storeAs: chosen
                    transitions:
                      - else: end
                  end:
                    type: terminal
                """;
        MagratheaWorkflowLoader realLoader = new MagratheaWorkflowLoader(null);
        when(workflowLoader.load(any(), any(), eq("withstore")))
                .thenReturn(Optional.of(realLoader.validateYaml("withstore", yamlWithStore)));
        when(workflowLoader.validateYaml(eq("withstore"), any()))
                .thenAnswer(inv -> realLoader.validateYaml("withstore", yamlWithStore));
        StartRecord start = StartRecord.builder()
                .workflowName("withstore").definitionYaml(yamlWithStore).build();
        when(journalService.readLast(any(), eq(StartRecord.class)))
                .thenReturn(Optional.of(start));

        String runId = workflowService.start("acme", "proj", "withstore", Map.of(), null);
        MagratheaTaskDocument firstTask = insertedTasks.get(0);
        appendedRecords.clear();

        TaskCompletedEvent event = new TaskCompletedEvent(
                "acme", "proj", runId, firstTask.getId(), "cap",
                MagratheaTaskType.CONDITION_TASK,
                TaskCompletedEvent.OUTCOME_SUCCESS,
                objectMapper.valueToTree(Map.of("picked", "end")),
                null, 1L, "end");

        workflowService.handleCompletion(event);

        assertThat(appendedRecords).hasAtLeastOneElementOfType(VarRecord.class);
        VarRecord var = (VarRecord) appendedRecords.stream()
                .filter(r -> r instanceof VarRecord).findFirst().orElseThrow();
        assertThat(var.getKey()).isEqualTo("chosen");
    }

    @Test
    void missing_required_param_throws_MagratheaWorkflowException() {
        String yamlWithReq = """
                start: end
                parameters:
                  required_one:
                    type: string
                    required: true
                states:
                  end:
                    type: terminal
                """;
        MagratheaWorkflowLoader realLoader = new MagratheaWorkflowLoader(null);
        when(workflowLoader.load(any(), any(), eq("req")))
                .thenReturn(Optional.of(realLoader.validateYaml("req", yamlWithReq)));

        assertThatThrownBy(() -> workflowService.start("acme", "proj", "req", Map.of(), null))
                .isInstanceOf(MagratheaWorkflowService.MagratheaWorkflowException.class)
                .hasMessageContaining("Required parameter");
        verify(taskService, never()).insert(any());
    }
}
