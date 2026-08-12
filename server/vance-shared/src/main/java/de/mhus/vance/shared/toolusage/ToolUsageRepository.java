package de.mhus.vance.shared.toolusage;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ToolUsageRepository extends MongoRepository<ToolUsageDocument, String> {

    /** Counters of one role in one project — the triage's tie-break input. */
    List<ToolUsageDocument> findByTenantIdAndProjectIdAndRecipeName(
            String tenantId, String projectId, String recipeName);

    /** Every role in a project — operator/analysis view, not the hot path. */
    List<ToolUsageDocument> findByTenantIdAndProjectId(String tenantId, String projectId);
}
