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

    /**
     * Prefix-filter on {@code path}, unpaged — the cascade listing
     * ({@code DocumentService.listByPrefixCascade}) narrows a folder to a
     * handful of rows with it.
     *
     * <p>Spring Data turns {@code StartsWith} into a {@code ^}-anchored regex,
     * which Mongo serves as a range scan on {@code tenant_project_path_idx}.
     * The alternative — fetching every active row of the project and filtering
     * in Java — is what this replaced, and it made the cost of a cascade
     * listing a function of the project's total document count.
     */
    List<DocumentDocument> findByTenantIdAndProjectIdAndStatusAndPathStartsWith(
            String tenantId, String projectId, DocumentStatus status, String pathPrefix);

    /** Prefix-filter on {@code path} for the path-filter UI. */
    Page<DocumentDocument> findByTenantIdAndProjectIdAndStatusAndPathStartsWith(
            String tenantId, String projectId, DocumentStatus status,
            String pathPrefix, Pageable pageable);

    Page<DocumentDocument> findByTenantIdAndProjectIdAndStatusAndKind(
            String tenantId, String projectId, DocumentStatus status,
            String kind, Pageable pageable);

    Page<DocumentDocument> findByTenantIdAndProjectIdAndStatusAndKindAndPathStartsWith(
            String tenantId, String projectId, DocumentStatus status,
            String kind, String pathPrefix, Pageable pageable);

    List<DocumentDocument> findByTenantIdAndProjectIdAndStatusAndKind(
            String tenantId, String projectId, DocumentStatus status, String kind);

    List<DocumentDocument> findByTenantIdAndProjectIdAndTagsContainingAndStatus(
            String tenantId, String projectId, String tag, DocumentStatus status);
}
