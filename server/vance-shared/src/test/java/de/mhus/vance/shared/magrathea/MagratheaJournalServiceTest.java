package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.NoteRecord;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import de.mhus.vance.shared.magrathea.journal.TaskResultRecord;
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

        StatusRecord record = StatusRecord.builder().status(MagratheaRunStatus.RUNNING).build();
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
                "acme", "proj", "run-1", "task-42",
                NoteRecord.builder().note("hi").build());

        assertThat(entry.getTaskId()).isEqualTo("task-42");
    }

    @Test
    void appendIfAbsent_returns_present_on_first_insert() {
        when(repo.save(any(MagratheaJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<MagratheaJournalEntry> result = service.appendIfAbsent(
                "acme", "proj", "run-1", "task-7",
                TaskResultRecord.builder().state("plan").outcome("success").taskId("task-7").build());

        assertThat(result).isPresent();
        assertThat(result.get().getTaskId()).isEqualTo("task-7");
    }

    @Test
    void appendIfAbsent_returns_empty_on_duplicate_key() {
        when(repo.save(any(MagratheaJournalEntry.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        Optional<MagratheaJournalEntry> result = service.appendIfAbsent(
                "acme", "proj", "run-1", "task-7",
                TaskResultRecord.builder().state("plan").outcome("success").taskId("task-7").build());

        assertThat(result).isEmpty();
    }

    @Test
    void readLast_walks_journal_from_the_back_for_matching_type() {
        MagratheaJournalEntry note = entry("note", NoteRecord.class.getName(),
                "{\"note\":\"first\"}", Instant.parse("2024-01-01T00:00:00Z"));
        MagratheaJournalEntry status1 = entry("s1", StatusRecord.class.getName(),
                "{\"status\":\"RUNNING\"}", Instant.parse("2024-01-01T00:00:01Z"));
        MagratheaJournalEntry status2 = entry("s2", StatusRecord.class.getName(),
                "{\"status\":\"DONE\"}", Instant.parse("2024-01-01T00:00:02Z"));
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
                eq("acme"), eq("proj"), eq("run-1")))
                .thenReturn(List.of(note, status1, status2));

        Optional<StatusRecord> last = service.readLast("acme", "proj", "run-1", StatusRecord.class);

        assertThat(last).isPresent();
        assertThat(last.get().getStatus()).isEqualTo(MagratheaRunStatus.DONE);
    }

    @Test
    void readLast_returns_empty_when_no_match() {
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
                eq("acme"), eq("proj"), eq("run-x")))
                .thenReturn(List.of());

        assertThat(service.readLast("acme", "proj", "run-x", StatusRecord.class)).isEmpty();
    }

    @Test
    void readAll_returns_only_entries_of_requested_type_in_order() {
        MagratheaJournalEntry start = entry("e1", StartRecord.class.getName(),
                "{\"workflowName\":\"x\",\"definitionYaml\":\"y\"}", Instant.parse("2024-01-01T00:00:00Z"));
        MagratheaJournalEntry note1 = entry("e2", NoteRecord.class.getName(),
                "{\"note\":\"a\"}", Instant.parse("2024-01-01T00:00:01Z"));
        MagratheaJournalEntry note2 = entry("e3", NoteRecord.class.getName(),
                "{\"note\":\"b\"}", Instant.parse("2024-01-01T00:00:02Z"));
        when(repo.findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
                eq("acme"), eq("proj"), eq("run-1")))
                .thenReturn(List.of(start, note1, note2));

        List<NoteRecord> notes = service.readAll("acme", "proj", "run-1", NoteRecord.class);

        assertThat(notes).extracting(NoteRecord::getNote).containsExactly("a", "b");
    }

    @Test
    void toRecord_with_typed_class_rejects_mismatch() {
        MagratheaJournalEntry e = entry("x", NoteRecord.class.getName(),
                "{\"note\":\"hello\"}", Instant.now());

        assertThatThrownBy(() -> service.toRecord(e, StatusRecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void toRecord_inferred_returns_empty_for_unknown_class() {
        MagratheaJournalEntry e = entry("x", "de.mhus.vance.shared.magrathea.journal.GhostRecord",
                "{}", Instant.now());

        Optional<JournalRecord> result = service.toRecord(e);

        assertThat(result).isEmpty();
    }

    @Test
    void toRecord_inferred_resolves_known_class() {
        MagratheaJournalEntry e = entry("x", NoteRecord.class.getName(),
                "{\"note\":\"yo\"}", Instant.now());

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
}
