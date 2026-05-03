package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ThinkProcessDocument}. Package-private —
 * callers go through {@link ThinkProcessService}.
 */
interface ThinkProcessRepository extends MongoRepository<ThinkProcessDocument, String> {

    Optional<ThinkProcessDocument> findByTenantIdAndSessionIdAndName(
            String tenantId, String sessionId, String name);

    List<ThinkProcessDocument> findByTenantIdAndSessionId(String tenantId, String sessionId);

    List<ThinkProcessDocument> findByTenantIdAndSessionIdAndStatus(
            String tenantId, String sessionId, ThinkProcessStatus status);

    /**
     * All children of {@code parentProcessId}, across sessions —
     * used by orchestrators (Eddie) that own workers in different
     * projects/sessions.
     */
    List<ThinkProcessDocument> findByTenantIdAndParentProcessId(
            String tenantId, String parentProcessId);

    boolean existsByTenantIdAndSessionIdAndName(
            String tenantId, String sessionId, String name);

    long deleteByTenantIdAndSessionId(String tenantId, String sessionId);
}
