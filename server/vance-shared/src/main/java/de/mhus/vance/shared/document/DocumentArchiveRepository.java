package de.mhus.vance.shared.document;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link DocumentArchiveDocument}. Package-private —
 * callers go through {@link DocumentArchiveService}.
 */
interface DocumentArchiveRepository extends MongoRepository<DocumentArchiveDocument, String> {

    List<DocumentArchiveDocument> findByTenantIdAndProjectIdAndLineageIdOrderByArchivedAtDesc(
            String tenantId, String projectId, String lineageId);

    long countByTenantIdAndProjectIdAndLineageId(
            String tenantId, String projectId, String lineageId);

    long deleteByTenantIdAndProjectIdAndLineageId(
            String tenantId, String projectId, String lineageId);
}
