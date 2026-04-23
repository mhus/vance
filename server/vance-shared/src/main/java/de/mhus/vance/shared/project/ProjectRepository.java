package de.mhus.vance.shared.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ProjectDocument}. Package-private — callers
 * go through {@link ProjectService}.
 */
interface ProjectRepository extends MongoRepository<ProjectDocument, String> {

    Optional<ProjectDocument> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantIdAndName(String tenantId, String name);

    List<ProjectDocument> findByTenantId(String tenantId);

    List<ProjectDocument> findByTenantIdAndProjectGroupId(String tenantId, String projectGroupId);

    List<ProjectDocument> findByTenantIdAndTeamIdsContaining(String tenantId, String teamId);
}
