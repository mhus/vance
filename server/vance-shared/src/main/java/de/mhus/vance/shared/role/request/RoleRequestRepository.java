package de.mhus.vance.shared.role.request;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Package-private data access for {@link RoleRequestDocument} — only
 * {@link RoleRequestService} (data sovereignty) touches it.
 */
interface RoleRequestRepository extends MongoRepository<RoleRequestDocument, String> {

    /**
     * Candidates for idempotent reuse. Operation and role are matched in the
     * service rather than here — the role is null for revokes, and one list
     * query beats several derived methods.
     */
    List<RoleRequestDocument> findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
            String tenantId,
            RoleRequestStatus status,
            RoleScopeType scopeType,
            String scopeId,
            RoleSubjectType subjectType,
            String subjectId);

    List<RoleRequestDocument> findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
            String tenantId, RoleSubjectType subjectType, String subjectId, RoleRequestStatus status);

    List<RoleRequestDocument> findByStatusAndCreatedAtBefore(RoleRequestStatus status, Instant cutoff);
}
