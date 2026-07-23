package de.mhus.vance.simpleauth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Package-private data access for {@link PermissionGrantDocument} — only
 * {@link PermissionGrantService} (data sovereignty) touches it.
 */
interface PermissionGrantRepository extends MongoRepository<PermissionGrantDocument, String> {

    List<PermissionGrantDocument> findByTenantIdAndScopeTypeAndScopeId(
            String tenantId, GrantScopeType scopeType, String scopeId);

    List<PermissionGrantDocument> findByTenantIdAndSubjectTypeAndSubjectId(
            String tenantId, GrantSubjectType subjectType, String subjectId);

    Optional<PermissionGrantDocument> findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
            String tenantId, GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId);
}
