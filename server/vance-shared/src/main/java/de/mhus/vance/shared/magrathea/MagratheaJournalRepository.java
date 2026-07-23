package de.mhus.vance.shared.magrathea;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MagratheaJournalEntry}. Package-private —
 * callers go through {@code MagratheaJournalService} in {@code vance-brain}.
 */
interface MagratheaJournalRepository extends MongoRepository<MagratheaJournalEntry, String> {

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
            String tenantId, String projectId, String workflowRunId);

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndTypeOrderByCreatedAtDesc(
            String tenantId, String projectId, String type,
            org.springframework.data.domain.Pageable pageable);

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndWorkflowRunIdAndTaskId(
            String tenantId, String projectId, String workflowRunId, String taskId);

    // Type-targeted reads — avoid loading the whole journal just to pick
    // out one record kind (was O(n) per read, O(n²) per transition).

    List<MagratheaJournalEntry> findByTenantIdAndProjectIdAndWorkflowRunIdAndTypeOrderByCreatedAtAsc(
            String tenantId, String projectId, String workflowRunId, String type);

    Optional<MagratheaJournalEntry>
            findFirstByTenantIdAndProjectIdAndWorkflowRunIdAndTypeOrderByCreatedAtDesc(
            String tenantId, String projectId, String workflowRunId, String type);

    long countByTenantIdAndProjectIdAndWorkflowRunIdAndType(
            String tenantId, String projectId, String workflowRunId, String type);

    Optional<MagratheaJournalEntry>
            findFirstByTenantIdAndProjectIdAndWorkflowRunIdOrderByCreatedAtAsc(
            String tenantId, String projectId, String workflowRunId);

    long deleteByTenantIdAndProjectIdAndWorkflowRunId(
            String tenantId, String projectId, String workflowRunId);
}
