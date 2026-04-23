package de.mhus.vance.shared.projectgroup;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ProjectGroupDocument}. Package-private —
 * callers go through {@link ProjectGroupService}.
 */
interface ProjectGroupRepository extends MongoRepository<ProjectGroupDocument, String> {

    Optional<ProjectGroupDocument> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantIdAndName(String tenantId, String name);

    List<ProjectGroupDocument> findByTenantId(String tenantId);
}
