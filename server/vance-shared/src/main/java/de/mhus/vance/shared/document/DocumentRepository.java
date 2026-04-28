package de.mhus.vance.shared.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link DocumentDocument}. Package-private — callers
 * go through {@link DocumentService}.
 */
interface DocumentRepository extends MongoRepository<DocumentDocument, String> {

    Optional<DocumentDocument> findByTenantIdAndProjectIdAndPath(
            String tenantId, String projectId, String path);

    boolean existsByTenantIdAndProjectIdAndPath(
            String tenantId, String projectId, String path);

    List<DocumentDocument> findByTenantIdAndProjectIdAndStatus(
            String tenantId, String projectId, DocumentStatus status);

    Page<DocumentDocument> findByTenantIdAndProjectIdAndStatus(
            String tenantId, String projectId, DocumentStatus status, Pageable pageable);

    List<DocumentDocument> findByTenantIdAndProjectIdAndTagsContainingAndStatus(
            String tenantId, String projectId, String tag, DocumentStatus status);
}
