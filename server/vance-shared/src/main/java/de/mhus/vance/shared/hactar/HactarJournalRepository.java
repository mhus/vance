package de.mhus.vance.shared.hactar;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link HactarJournalEntry}. Package-private —
 * callers go through {@code HactarJournalService} in {@code vance-brain}.
 */
interface HactarJournalRepository extends MongoRepository<HactarJournalEntry, String> {

    List<HactarJournalEntry> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);

    List<HactarJournalEntry> findByWorkflowRunIdAndType(String workflowRunId, String type, Sort sort);

    List<HactarJournalEntry> findByWorkflowRunIdAndTaskId(String workflowRunId, String taskId);

    long deleteByWorkflowRunId(String workflowRunId);
}
