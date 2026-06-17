package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ToolHealthRepository extends MongoRepository<ToolHealthDocument, String> {

    Optional<ToolHealthDocument> findByTenantIdAndScopeAndScopeIdAndToolName(
            String tenantId, ToolHealthScope scope, String scopeId, String toolName);

    /** Listing for the Insights UI — all health records inside one scope. */
    List<ToolHealthDocument> findByTenantIdAndScopeAndScopeId(
            String tenantId, ToolHealthScope scope, String scopeId);
}
