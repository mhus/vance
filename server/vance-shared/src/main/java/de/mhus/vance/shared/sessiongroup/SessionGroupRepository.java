package de.mhus.vance.shared.sessiongroup;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SessionGroupDocument}. Package-private —
 * callers go through {@link SessionGroupService}.
 */
interface SessionGroupRepository extends MongoRepository<SessionGroupDocument, String> {

    List<SessionGroupDocument> findByTenantIdAndProjectIdAndUserIdOrderBySortIndexAsc(
            String tenantId, String projectId, String userId);

    Optional<SessionGroupDocument> findByTenantIdAndProjectIdAndUserIdAndName(
            String tenantId, String projectId, String userId, String name);

    boolean existsByTenantIdAndProjectIdAndUserIdAndName(
            String tenantId, String projectId, String userId, String name);
}
