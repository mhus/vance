package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.NoteRecord;
import de.mhus.vance.shared.magrathea.journal.ResultRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StateEnteredRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.VarRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replay-logic tests for {@link MagratheaStateProjector}. Drives a real
 * {@link MagratheaJournalService} (real Jackson serialisation) over a
 * Mockito-backed repository so the projector sees the same byte stream
 * a production run would produce.
 */
class MagratheaStateProjectorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MagratheaJournalRepository repo = mock(MagratheaJournalRepository.class);
    private final MagratheaJournalService journal;
    private final MagratheaStateProjector projector;

    private final List<MagratheaJournalEntry> entries = new ArrayList<>();
    private Instant tick = Instant.parse("2024-01-01T00:00:00Z");

    MagratheaStateProjectorTest() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> {
            MagratheaJournalEntry e = inv.getArgument(0);
            e.setId("e" + (entries.size() + 1));
            tick = tick.plusMillis(1);
            e.setCreatedAt(tick);
            entries.add(e);
            return e;
        });
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
                eq("acme"), eq("proj"), eq("r1")))
                .thenAnswer(inv -> entries.stream()
                        .filter(e -> "r1".equals(e.getWorkflowRunId()))
                        .toList());

        journal = new MagratheaJournalService(repo, objectMapper);
        projector = new MagratheaStateProjector(journal, objectMapper);
    }

    @Test
    void project_returns_empty_when_run_has_no_journal() {
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
                eq("acme"), eq("proj"), eq("missing"))).thenReturn(List.of());

        assertThat(projector.project("acme", "proj", "missing")).isEmpty();
    }

    @Test
    void project_returns_empty_when_no_start_record_yet() {
        append(NoteRecord.builder().note("orphan").build());

        assertThat(projector.project("acme", "proj", "r1")).isEmpty();
    }

    @Test
    void project_running_workflow_returns_RUNNING_with_current_state() {
        append(StartRecord.builder()
                .workflowName("pr-review")
                .workflowVersion("1")
                .definitionYaml("...")
                .params(Map.of("pr_url", "https://github.com/x/y/pull/1"))
                .startedBy("alice")
                .build());
        append(StateEnteredRecord.builder().state("plan").build());

        MagratheaProcessDto dto = projector.project("acme", "proj", "r1").orElseThrow();

        assertThat(dto.getWorkflowRunId()).isEqualTo("r1");
        assertThat(dto.getWorkflowName()).isEqualTo("pr-review");
        assertThat(dto.getWorkflowVersion()).isEqualTo("1");
        assertThat(dto.getStatus()).isEqualTo(MagratheaRunStatus.RUNNING);
        assertThat(dto.getCurrentState()).isEqualTo("plan");
        assertThat(dto.getStartedBy()).isEqualTo("alice");
        assertThat(dto.getParams()).containsEntry("pr_url", "https://github.com/x/y/pull/1");
        assertThat(dto.getTerminatedAt()).isNull();
    }

    @Test
    void project_replays_vars_in_journal_order_with_later_writes_winning() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());
        append(VarRecord.builder().key("risk").value(objectMapper.valueToTree("high")).build());
        append(VarRecord.builder().key("count").value(objectMapper.valueToTree(3)).build());
        append(VarRecord.builder().key("risk").value(objectMapper.valueToTree("low")).build());

        MagratheaProcessDto dto = projector.project("acme", "proj", "r1").orElseThrow();

        assertThat(dto.getVars()).containsEntry("risk", "low").containsEntry("count", 3);
    }

    @Test
    void project_marks_terminal_DONE_with_terminatedAt_and_result() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());
        append(StateEnteredRecord.builder().state("done").build());
        append(ResultRecord.builder()
                .state("done")
                .result(objectMapper.valueToTree(Map.of("summary", "merged")))
                .build());
        append(StatusRecord.builder().status(MagratheaRunStatus.DONE).build());

        MagratheaProcessDto dto = projector.project("acme", "proj", "r1").orElseThrow();

        assertThat(dto.getStatus()).isEqualTo(MagratheaRunStatus.DONE);
        assertThat(dto.getCurrentState()).isEqualTo("done");
        assertThat(dto.getTerminatedAt()).isNotNull();
        assertThat(dto.getResult()).containsEntry("summary", "merged");
    }

    @Test
    void project_marks_terminal_FAILED_status() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());
        append(StateEnteredRecord.builder().state("escalate").build());
        append(StatusRecord.builder().status(MagratheaRunStatus.FAILED).reason("checks failed").build());

        MagratheaProcessDto dto = projector.project("acme", "proj", "r1").orElseThrow();

        assertThat(dto.getStatus()).isEqualTo(MagratheaRunStatus.FAILED);
        assertThat(dto.getCurrentState()).isEqualTo("escalate");
        assertThat(dto.getTerminatedAt()).isNotNull();
    }

    @Test
    void projectStatus_returns_RUNNING_when_no_status_record_yet() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());

        assertThat(projector.projectStatus("acme", "proj", "r1")).isEqualTo(MagratheaRunStatus.RUNNING);
    }

    @Test
    void projectStatus_returns_latest_status() {
        append(StatusRecord.builder().status(MagratheaRunStatus.RUNNING).build());
        append(StatusRecord.builder().status(MagratheaRunStatus.PAUSED).build());
        append(StatusRecord.builder().status(MagratheaRunStatus.DONE).build());

        assertThat(projector.projectStatus("acme", "proj", "r1")).isEqualTo(MagratheaRunStatus.DONE);
    }

    @Test
    void projectVars_returns_empty_map_when_no_vars() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());

        assertThat(projector.projectVars("acme", "proj", "r1")).isEmpty();
    }

    @Test
    void projectVars_handles_object_values() {
        append(VarRecord.builder()
                .key("plan_output")
                .value(objectMapper.valueToTree(Map.of("risk", "low", "tests_passed", true)))
                .build());

        Map<String, Object> vars = projector.projectVars("acme", "proj", "r1");

        assertThat(vars).containsKey("plan_output");
        @SuppressWarnings("unchecked")
        Map<String, Object> planOutput = (Map<String, Object>) vars.get("plan_output");
        assertThat(planOutput).containsEntry("risk", "low").containsEntry("tests_passed", true);
    }

    @Test
    void project_keeps_currentState_to_last_entered_when_terminal_reached() {
        append(StartRecord.builder().workflowName("x").definitionYaml("y").build());
        append(StateEnteredRecord.builder().state("plan").build());
        append(TaskResultRecord.builder()
                .state("plan").taskId("t1").outcome("success").build());
        append(StateEnteredRecord.builder().state("done").build());
        append(StatusRecord.builder().status(MagratheaRunStatus.DONE).build());

        MagratheaProcessDto dto = projector.project("acme", "proj", "r1").orElseThrow();

        assertThat(dto.getCurrentState()).isEqualTo("done");
    }

    // ──────────── helper ────────────

    private void append(JournalRecord record) {
        journal.append("acme", "proj", "r1", record);
    }
}
