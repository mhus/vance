package de.mhus.vance.simpleauth;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Package-private data access for {@link PermissionRequestDocument} — only
 * {@link PermissionRequestService} (data sovereignty) touches it.
 */
interface PermissionRequestRepository extends MongoRepository<PermissionRequestDocument, String> {

    /**
     * Candidates for idempotent reuse. Operation and role are matched in
     * the service rather than here — {@code role} is null for revokes,
     * and one list query beats two derived methods.
     */
    List<PermissionRequestDocument> findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
            String tenantId, PermissionRequestStatus status,
            GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId);

    List<PermissionRequestDocument> findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
            String tenantId, GrantSubjectType subjectType, String subjectId,
            PermissionRequestStatus status);

    List<PermissionRequestDocument> findByStatusAndCreatedAtBefore(
            PermissionRequestStatus status, Instant cutoff);
}
