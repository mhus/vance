package de.mhus.vance.shared.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarRunStatus;
import de.mhus.vance.shared.hactar.journal.JournalRecord;
import de.mhus.vance.shared.hactar.journal.NoteRecord;
import de.mhus.vance.shared.hactar.journal.StartRecord;
import de.mhus.vance.shared.hactar.journal.StatusRecord;
import de.mhus.vance.shared.hactar.journal.TaskResultRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure-logic tests for {@link HactarJournalService} with a mocked
 * {@link HactarJournalRepository}. Covers the three responsibilities:
 * serialization on append, idempotent insert via the unique-index
 * contract, and typed deserialisation on read.
 */
class HactarJournalServiceTest {

    private final HactarJournalRepository repo = mock(HactarJournalRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final HactarJournalService service = new HactarJournalService(repo, objectMapper);

    @Test
    void append_serialises_record_and_sets_type_to_fqn() {
        when(repo.save(any(HactarJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        StatusRecord record = StatusRecord.builder().status(HactarRunStatus.RUNNING).build();
        HactarJournalEntry entry = service.append("acme", "proj", "run-1", record);

        assertThat(entry.getTenantId()).isEqualTo("acme");
        assertThat(entry.getProjectId()).isEqualTo("proj");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(entry.getType()).isEqualTo(StatusRecord.class.getName());
        assertThat(entry.getData()).contains("\"status\":\"RUNNING\"");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void append_carries_taskId_when_supplied() {
        when(repo.save(any(HactarJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        HactarJournalEntry entry = service.append(
                "acme", "proj", "run-1", "task-42",
                NoteRecord.builder().note("hi").build());

        assertThat(entry.getTaskId()).isEqualTo("task-42");
    }

    @Test
    void appendIfAbsent_returns_present_on_first_insert() {
        when(repo.save(any(HactarJournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<HactarJournalEntry> result = service.appendIfAbsent(
                "acme", "proj", "run-1", "task-7",
                TaskResultRecord.builder().state("plan").outcome("success").taskId("task-7").build());

        assertThat(result).isPresent();
        assertThat(result.get().getTaskId()).isEqualTo("task-7");
    }

    @Test
    void appendIfAbsent_returns_empty_on_duplicate_key() {
        when(repo.save(any(HactarJournalEntry.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        Optional<HactarJournalEntry> result = service.appendIfAbsent(
                "acme", "proj", "run-1", "task-7",
                TaskResultRecord.builder().state("plan").outcome("success").taskId("task-7").build());

        assertThat(result).isEmpty();
    }

    @Test
    void readLast_walks_journal_from_the_back_for_matching_type() {
        HactarJournalEntry note = entry("note", NoteRecord.class.getName(),
                "{\"note\":\"first\"}", Instant.parse("2024-01-01T00:00:00Z"));
        HactarJournalEntry status1 = entry("s1", StatusRecord.class.getName(),
                "{\"status\":\"RUNNING\"}", Instant.parse("2024-01-01T00:00:01Z"));
        HactarJournalEntry status2 = entry("s2", StatusRecord.class.getName(),
                "{\"status\":\"DONE\"}", Instant.parse("2024-01-01T00:00:02Z"));
        when(repo.findByWorkflowRunIdOrderByCreatedAtAsc(eq("run-1")))
                .thenReturn(List.of(note, status1, status2));

        Optional<StatusRecord> last = service.readLast("run-1", StatusRecord.class);

        assertThat(last).isPresent();
        assertThat(last.get().getStatus()).isEqualTo(HactarRunStatus.DONE);
    }

    @Test
    void readLast_returns_empty_when_no_match() {
        when(repo.findByWorkflowRunIdOrderByCreatedAtAsc(eq("run-x")))
                .thenReturn(List.of());

        assertThat(service.readLast("run-x", StatusRecord.class)).isEmpty();
    }

    @Test
    void readAll_returns_only_entries_of_requested_type_in_order() {
        HactarJournalEntry start = entry("e1", StartRecord.class.getName(),
                "{\"workflowName\":\"x\",\"definitionYaml\":\"y\"}", Instant.parse("2024-01-01T00:00:00Z"));
        HactarJournalEntry note1 = entry("e2", NoteRecord.class.getName(),
                "{\"note\":\"a\"}", Instant.parse("2024-01-01T00:00:01Z"));
        HactarJournalEntry note2 = entry("e3", NoteRecord.class.getName(),
                "{\"note\":\"b\"}", Instant.parse("2024-01-01T00:00:02Z"));
        when(repo.findByWorkflowRunIdOrderByCreatedAtAsc(eq("run-1")))
                .thenReturn(List.of(start, note1, note2));

        List<NoteRecord> notes = service.readAll("run-1", NoteRecord.class);

        assertThat(notes).extracting(NoteRecord::getNote).containsExactly("a", "b");
    }

    @Test
    void toRecord_with_typed_class_rejects_mismatch() {
        HactarJournalEntry e = entry("x", NoteRecord.class.getName(),
                "{\"note\":\"hello\"}", Instant.now());

        assertThatThrownBy(() -> service.toRecord(e, StatusRecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void toRecord_inferred_returns_empty_for_unknown_class() {
        HactarJournalEntry e = entry("x", "de.mhus.vance.shared.hactar.journal.GhostRecord",
                "{}", Instant.now());

        Optional<JournalRecord> result = service.toRecord(e);

        assertThat(result).isEmpty();
    }

    @Test
    void toRecord_inferred_resolves_known_class() {
        HactarJournalEntry e = entry("x", NoteRecord.class.getName(),
                "{\"note\":\"yo\"}", Instant.now());

        Optional<JournalRecord> result = service.toRecord(e);

        assertThat(result).isPresent();
        assertThat(((NoteRecord) result.get()).getNote()).isEqualTo("yo");
    }

    private static HactarJournalEntry entry(String id, String type, String data, Instant at) {
        return HactarJournalEntry.builder()
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
