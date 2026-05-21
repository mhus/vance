package de.mhus.vance.shared.magrathea;

import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only journal owner for the Magrathea workflow subsystem. Holds
 * datahoheit over the {@code magrathea_journal} collection — all writes
 * and reads of journal entries go through this service.
 *
 * <p>Typed {@link JournalRecord} bodies are Jackson-serialised into the
 * generic {@link MagratheaJournalEntry} container. The {@code type} field
 * stores the FQN so {@link #toRecord} can reconstitute the original
 * subtype. Pattern modelled after Nimbus'
 * {@code WWorkflowJournalService} — see
 * {@code planning/workflow-service.md} §3.2.
 *
 * <p>The idempotent {@link #appendIfAbsent} relies on the partial
 * unique index defined on {@link MagratheaJournalEntry} so the second
 * append of a {@code TaskResultRecord} for the same {@code taskId} is
 * silently dropped at the Mongo layer instead of producing a duplicate
 * (plan §11.1).
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaJournalService {

    private final MagratheaJournalRepository repository;
    private final ObjectMapper objectMapper;

    // ──────────── append ────────────

    /** Append a record that isn't tied to a specific task (status, start, note, …). */
    public MagratheaJournalEntry append(
            String tenantId, String projectId, String workflowRunId, JournalRecord record) {
        return append(tenantId, projectId, workflowRunId, null, record);
    }

    /** Append a record optionally linked to a task row. */
    public MagratheaJournalEntry append(
            String tenantId,
            String projectId,
            String workflowRunId,
            @Nullable String taskId,
            JournalRecord record) {
        MagratheaJournalEntry entry = buildEntry(tenantId, projectId, workflowRunId, taskId, record);
        return repository.save(entry);
    }

    /**
     * Append a record that must be unique within
     * {@code (workflowRunId, taskId, type)}. Used for
     * {@code TaskResultRecord} appends so a duplicate completion event
     * (pod-reclaim race, retried listener) cannot land twice.
     *
     * @return the saved entry, or {@link Optional#empty()} when Mongo
     *         rejected the insert via the partial-unique index.
     */
    public Optional<MagratheaJournalEntry> appendIfAbsent(
            String tenantId,
            String projectId,
            String workflowRunId,
            String taskId,
            JournalRecord record) {
        MagratheaJournalEntry entry = buildEntry(tenantId, projectId, workflowRunId, taskId, record);
        try {
            return Optional.of(repository.save(entry));
        } catch (DuplicateKeyException ex) {
            log.debug("appendIfAbsent: duplicate {} for runId={} taskId={} dropped",
                    record.getClass().getSimpleName(), workflowRunId, taskId);
            return Optional.empty();
        }
    }

    private MagratheaJournalEntry buildEntry(
            String tenantId,
            String projectId,
            String workflowRunId,
            @Nullable String taskId,
            JournalRecord record) {
        String data;
        try {
            data = objectMapper.writeValueAsString(record);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Cannot serialise journal record " + record.getClass().getName(), ex);
        }
        return MagratheaJournalEntry.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .workflowRunId(workflowRunId)
                .taskId(taskId)
                .type(record.getClass().getName())
                .data(data)
                .createdAt(Instant.now())
                .build();
    }

    // ──────────── read ────────────

    /** All entries for a run, ordered ascending by creation time. */
    public List<MagratheaJournalEntry> read(String workflowRunId) {
        return repository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
    }

    /** Entries linked to one task — used by the executor for replay during reclaim. */
    public List<MagratheaJournalEntry> readByTaskId(String workflowRunId, String taskId) {
        return repository.findByWorkflowRunIdAndTaskId(workflowRunId, taskId);
    }

    /** When the run started — the timestamp of its first journal entry. */
    public Optional<java.time.Instant> firstCreatedAt(String workflowRunId) {
        List<MagratheaJournalEntry> entries = repository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        if (entries.isEmpty()) return Optional.empty();
        return Optional.ofNullable(entries.get(0).getCreatedAt());
    }

    /** Count entries of a typed record subclass — used for bounds enforcement. */
    public <T extends JournalRecord> long count(String workflowRunId, Class<T> recordType) {
        return repository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId).stream()
                .filter(e -> recordType.getName().equals(e.getType()))
                .count();
    }

    /**
     * Most recent typed record of the given subclass — analog Nimbus'
     * {@code WorkflowContext.getLastJournalRecord(Class)}.
     */
    public <T extends JournalRecord> Optional<T> readLast(
            String workflowRunId, Class<T> recordType) {
        List<MagratheaJournalEntry> entries = repository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            MagratheaJournalEntry entry = entries.get(i);
            if (recordType.getName().equals(entry.getType())) {
                return Optional.of(deserialize(entry, recordType));
            }
        }
        return Optional.empty();
    }

    /**
     * All entries of the given subclass, in journal order. Useful for
     * variable replay (typed {@link de.mhus.vance.shared.magrathea.journal.VarRecord})
     * and audit trails.
     */
    public <T extends JournalRecord> List<T> readAll(String workflowRunId, Class<T> recordType) {
        List<MagratheaJournalEntry> entries = repository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        List<T> out = new ArrayList<>();
        for (MagratheaJournalEntry entry : entries) {
            if (recordType.getName().equals(entry.getType())) {
                out.add(deserialize(entry, recordType));
            }
        }
        return out;
    }

    /** Re-hydrate a journal entry's stored body into its typed subclass. */
    public <T extends JournalRecord> T toRecord(MagratheaJournalEntry entry, Class<T> recordType) {
        if (!recordType.getName().equals(entry.getType())) {
            throw new IllegalArgumentException(
                    "Journal entry type '" + entry.getType()
                            + "' does not match requested " + recordType.getName());
        }
        return deserialize(entry, recordType);
    }

    /**
     * Re-hydrate using the FQN stored in the entry — caller does not
     * need to know the concrete subtype up front. Used by the projector
     * when walking the journal end-to-end.
     */
    public Optional<JournalRecord> toRecord(MagratheaJournalEntry entry) {
        try {
            Class<?> clazz = Class.forName(entry.getType());
            if (!JournalRecord.class.isAssignableFrom(clazz)) {
                log.warn("Journal entry type '{}' is not a JournalRecord — skipping", entry.getType());
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Class<? extends JournalRecord> recordType = (Class<? extends JournalRecord>) clazz;
            return Optional.of(deserialize(entry, recordType));
        } catch (ClassNotFoundException ex) {
            log.warn("Journal entry type '{}' is unknown — skipping", entry.getType());
            return Optional.empty();
        }
    }

    private <T extends JournalRecord> T deserialize(MagratheaJournalEntry entry, Class<T> recordType) {
        try {
            return objectMapper.readValue(entry.getData(), recordType);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Cannot deserialise journal entry id=" + entry.getId()
                            + " as " + recordType.getName(), ex);
        }
    }

    // ──────────── admin ────────────

    /** Drop the whole journal of a run — admin / test fixture. */
    public long deleteRun(String workflowRunId) {
        return repository.deleteByWorkflowRunId(workflowRunId);
    }
}
