package de.mhus.vance.shared.toolusage;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ToolUsageRepository extends MongoRepository<ToolUsageDocument, String> {

    List<ToolUsageDocument> findByTenantIdAndProjectId(String tenantId, String projectId);
}
