package de.mhus.vance.shared.magrathea;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MagratheaJournalEntry}. Package-private —
 * callers go through {@code MagratheaJournalService} in {@code vance-brain}.
 */
interface MagratheaJournalRepository extends MongoRepository<MagratheaJournalEntry, String> {

    List<MagratheaJournalEntry> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);

    List<MagratheaJournalEntry> findByWorkflowRunIdAndType(String workflowRunId, String type, Sort sort);

    List<MagratheaJournalEntry> findByWorkflowRunIdAndTaskId(String workflowRunId, String taskId);

    long deleteByWorkflowRunId(String workflowRunId);
}
