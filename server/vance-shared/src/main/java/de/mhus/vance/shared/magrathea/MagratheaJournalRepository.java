package de.mhus.vance.shared.magrathea;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MagratheaJournalEntry}. Package-private —
 * callers go through {@code MagratheaJournalService} in {@code vance-brain}.
 */
interface MagratheaJournalRepository extends MongoRepository<MagratheaJournalEntry, String> {

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
            String tenantId, String projectId, String workflowRunId);

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndWorkflowRunIdAndTaskId(
            String tenantId, String projectId, String workflowRunId, String taskId);

    long deleteByTenantIdAndProjectIdAndWorkflowRunId(
            String tenantId, String projectId, String workflowRunId);
}
