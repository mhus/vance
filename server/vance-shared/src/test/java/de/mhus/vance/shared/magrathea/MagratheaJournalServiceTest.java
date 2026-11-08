package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.NoteRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
import de.mhus.vance.shared.magrathea.journal.TaskStartedRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure-logic tests for {@link MagratheaJournalService} with a mocked
 * {@link MagratheaJournalRepository}. Covers the three responsibilities:
 * serialization on append, idempotent insert via the unique-index
 * contract, and typed deserialisation on read.
 */
class MagratheaJournalServiceTest {

    private final MagratheaJournalRepository repo = mock(MagratheaJournalRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MagratheaJournalService service = new MagratheaJournalService(repo, objectMapper);

    @Test
    void append_serialises_record_and_sets_type_to_fqn() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        StatusRecord record =
                StatusRecord.builder().status(MagratheaRunStatus.RUNNING).build();
        MagratheaJournalEntry entry = service.append("acme", "proj", "run-1", record);

        assertThat(entry.getTenantId()).isEqualTo("acme");
        assertThat(entry.getProjectId()).isEqualTo("proj");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(entry.getType()).isEqualTo(StatusRecord.class.getName());
        assertThat(entry.getData()).contains("\"status\":\"RUNNING\"");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void append_carries_taskId_when_supplied() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MagratheaJournalEntry entry = service.append(
                "acme",
                "proj",
                "run-1",
                "task-42",
                NoteRecord.builder().note("hi").build());

        assertThat(entry.getTaskId()).isEqualTo("task-42");
    }

    @Test
    void appendIfAbsent_returns_present_on_first_insert() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<MagratheaJournalEntry> result = service.appendIfAbsent(
                "acme",
                "proj",
                "run-1",
                "task-7",
                TaskResultRecord.builder()
                        .state("plan")
                        .outcome("success")
                        .taskId("task-7")
                        .build());

        assertThat(result).isPresent();
        assertThat(result.get().getTaskId()).isEqualTo("task-7");
    }

    @Test
    void appendIfAbsent_returns_empty_on_duplicate_key() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenThrow(new DuplicateKeyException("dup"));

        Optional<MagratheaJournalEntry> result = service.appendIfAbsent(
                "acme",
                "proj",
                "run-1",
                "task-7",
                TaskResultRecord.builder()
                        .state("plan")
                        .outcome("success")
                        .taskId("task-7")
                        .build());

        assertThat(result).isEmpty();
    }

    @Test
    void readLast_returns_deserialised_latest_of_type_from_targeted_query() {
        MagratheaJournalEntry status2 = entry(
                "s2", StatusRecord.class.getName(), "{\"status\":\"DONE\"}", Instant.parse("2024-01-01T00:00:02Z"));
        // The DB does the ordering now — readLast asks for the newest of type.
        when(repo.findFirstByTenantIdAndProjectIdAndWorkflowRunIdAndTypeOrderByCreatedAtDesc(
                        eq("acme"), eq("proj"), eq("run-1"), eq(StatusRecord.class.getName())))
                .thenReturn(Optional.of(status2));

        Optional<StatusRecord> last = service.readLast("acme", "proj", "run-1", StatusRecord.class);

        assertThat(last).isPresent();
        assertThat(last.get().getStatus()).isEqualTo(MagratheaRunStatus.DONE);
    }

    @Test
    void readLast_returns_empty_when_no_match() {
        when(repo.findFirstByTenantIdAndProjectIdAndWorkflowRunIdAndTypeOrderByCreatedAtDesc(
                        eq("acme"), eq("proj"), eq("run-x"), eq(StatusRecord.class.getName())))
                .thenReturn(Optional.empty());

        assertThat(service.readLast("acme", "proj", "run-x", StatusRecord.class))
                .isEmpty();
    }

    @Test
    void readAll_returns_entries_of_requested_type_in_order() {
        MagratheaJournalEntry note1 =
                entry("e2", NoteRecord.class.getName(), "{\"note\":\"a\"}", Instant.parse("2024-01-01T00:00:01Z"));
        MagratheaJournalEntry note2 =
                entry("e3", NoteRecord.class.getName(), "{\"note\":\"b\"}", Instant.parse("2024-01-01T00:00:02Z"));
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdAndTypeOrderByCreatedAtAsc(
                        eq("acme"), eq("proj"), eq("run-1"), eq(NoteRecord.class.getName())))
                .thenReturn(List.of(note1, note2));

        List<NoteRecord> notes = service.readAll("acme", "proj", "run-1", NoteRecord.class);

        assertThat(notes).extracting(NoteRecord::getNote).containsExactly("a", "b");
    }

    @Test
    void toRecord_with_typed_class_rejects_mismatch() {
        MagratheaJournalEntry e = entry("x", NoteRecord.class.getName(), "{\"note\":\"hello\"}", Instant.now());

        assertThatThrownBy(() -> service.toRecord(e, StatusRecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void toRecord_inferred_returns_empty_for_unknown_class() {
        MagratheaJournalEntry e = entry("x", "de.mhus.vance.shared.magrathea.journal.GhostRecord", "{}", Instant.now());

        Optional<JournalRecord> result = service.toRecord(e);

        assertThat(result).isEmpty();
    }

    @Test
    void toRecord_inferred_resolves_known_class() {
        MagratheaJournalEntry e = entry("x", NoteRecord.class.getName(), "{\"note\":\"yo\"}", Instant.now());

        Optional<JournalRecord> result = service.toRecord(e);

        assertThat(result).isPresent();
        assertThat(((NoteRecord) result.get()).getNote()).isEqualTo("yo");
    }

    private static MagratheaJournalEntry entry(String id, String type, String data, Instant at) {
        return MagratheaJournalEntry.builder()
                .id(id)
                .tenantId("acme")
                .projectId("proj")
                .workflowRunId("run-1")
                .type(type)
                .data(data)
                .createdAt(at)
                .build();
    }

    // ──────────── recentTaskExecutions ────────────

    private MagratheaJournalEntry started(String run, String task, String state, String pod, Instant at) {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        MagratheaJournalEntry entry = service.append(
                "acme",
                "proj",
                run,
                task,
                TaskStartedRecord.builder()
                        .state(state)
                        .taskType(MagratheaTaskType.AGENT_TASK)
                        .taskId(task)
                        .claimedBy(pod)
                        .build());
        entry.setCreatedAt(at);
        return entry;
    }

    private MagratheaJournalEntry result(String run, String task, String outcome, long durationMs, Instant at) {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        MagratheaJournalEntry entry = service.append(
                "acme",
                "proj",
                run,
                task,
                TaskResultRecord.builder()
                        .state("s")
                        .taskId(task)
                        .outcome(outcome)
                        .durationMs(durationMs)
                        .build());
        entry.setCreatedAt(at);
        return entry;
    }

    private void stubJournal(List<MagratheaJournalEntry> startsNewestFirst, List<MagratheaJournalEntry> results) {
        when(repo.findByTenantIdAndProjectIdAndTypeOrderByCreatedAtDesc(
                        eq("acme"), eq("proj"), eq(TaskStartedRecord.class.getName()), any()))
                .thenReturn(startsNewestFirst);
        when(repo.findByTenantIdAndProjectIdAndTypeAndCreatedAtGreaterThanEqual(
                        eq("acme"), eq("proj"), eq(TaskResultRecord.class.getName()), any()))
                .thenReturn(results);
    }

    @Test
    void recentTaskExecutions_pairs_start_with_result_and_names_the_pod() {
        Instant t0 = Instant.parse("2026-11-08T10:00:00Z");
        MagratheaJournalEntry start = started("run-1", "t1", "fetch", "brain-a", t0);
        MagratheaJournalEntry done = result("run-1", "t1", "success", 4200, t0.plusSeconds(4));
        stubJournal(List.of(start), List.of(done));

        List<MagratheaJournalService.TaskExecution> out = service.recentTaskExecutions("acme", "proj", 50);

        assertThat(out).hasSize(1);
        MagratheaJournalService.TaskExecution e = out.get(0);
        assertThat(e.state()).isEqualTo("fetch");
        assertThat(e.podId()).isEqualTo("brain-a");
        assertThat(e.startedAt()).isEqualTo(t0);
        assertThat(e.finishedAt()).isEqualTo(t0.plusSeconds(4));
        assertThat(e.durationMs()).isEqualTo(4200L);
        assertThat(e.outcome()).isEqualTo("success");
    }

    @Test
    void recentTaskExecutions_marks_start_without_result_as_running() {
        MagratheaJournalEntry start = started("run-1", "t1", "fetch", "brain-a", Instant.parse("2026-11-08T10:00:00Z"));
        stubJournal(List.of(start), List.of());

        MagratheaJournalService.TaskExecution e =
                service.recentTaskExecutions("acme", "proj", 50).get(0);

        assertThat(e.outcome()).isEqualTo(MagratheaJournalService.OUTCOME_RUNNING);
        assertThat(e.finishedAt()).isNull();
        assertThat(e.durationMs()).isNull();
    }

    @Test
    void recentTaskExecutions_gives_the_result_only_to_the_latest_start_of_a_task() {
        Instant t0 = Instant.parse("2026-11-08T10:00:00Z");
        MagratheaJournalEntry first = started("run-1", "t1", "fetch", "brain-a", t0);
        MagratheaJournalEntry retry = started("run-1", "t1", "fetch", "brain-b", t0.plusSeconds(60));
        MagratheaJournalEntry done = result("run-1", "t1", "success", 900, t0.plusSeconds(61));
        stubJournal(List.of(retry, first), List.of(done));

        List<MagratheaJournalService.TaskExecution> out = service.recentTaskExecutions("acme", "proj", 50);

        assertThat(out).extracting(MagratheaJournalService.TaskExecution::podId).containsExactly("brain-b", "brain-a");
        assertThat(out.get(0).outcome()).isEqualTo("success");
        assertThat(out.get(1).outcome()).isEqualTo(MagratheaJournalService.OUTCOME_RESTARTED);
    }

    @Test
    void recentTaskExecutions_is_empty_without_any_task_start() {
        stubJournal(List.of(), List.of());

        assertThat(service.recentTaskExecutions("acme", "proj", 50)).isEmpty();
    }
}
