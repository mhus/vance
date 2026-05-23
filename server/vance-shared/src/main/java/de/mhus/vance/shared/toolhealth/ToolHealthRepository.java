package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ToolHealthRepository extends MongoRepository<ToolHealthDocument, String> {

    Optional<ToolHealthDocument> findByTenantIdAndScopeAndScopeIdAndToolName(
            String tenantId, ToolHealthScope scope, String scopeId, String toolName);
}
