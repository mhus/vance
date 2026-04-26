package de.mhus.vance.shared.rag;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link RagDocument}. Package-private —
 * callers go through {@link RagCatalogService}.
 */
interface RagRepository extends MongoRepository<RagDocument, String> {

    Optional<RagDocument> findByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);

    List<RagDocument> findByTenantIdAndProjectId(String tenantId, String projectId);

    boolean existsByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);
}
