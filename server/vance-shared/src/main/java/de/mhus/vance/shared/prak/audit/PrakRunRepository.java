package de.mhus.vance.shared.prak.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo repository for {@link PrakRunRecord}. Reads are scoped to
 * tenant + project via the compound index defined on the document.
 */
public interface PrakRunRepository extends MongoRepository<PrakRunRecord, String> {

    /**
     * Most-recent-first list of runs for a tenant+project. Pageable
     * so dashboard callers can scroll the audit trail.
     */
    List<PrakRunRecord> findByTenantIdAndProjectIdOrderByCreatedAtDesc(
            String tenantId, String projectId, Pageable pageable);

    /**
     * Most-recent-first list of runs for one specific think-process —
     * drives the Insights "Prak Runs" tab.
     */
    List<PrakRunRecord> findByTenantIdAndProcessIdOrderByCreatedAtDesc(
            String tenantId, String processId, Pageable pageable);

    /** All records for a given runId — typically 0 or 1 entries. */
    List<PrakRunRecord> findByRunId(String runId);
}
