package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Package-private data access for {@link RoleAssignmentDocument} — only
 * {@link RoleAssignmentService} (data sovereignty) touches it.
 */
interface RoleAssignmentRepository extends MongoRepository<RoleAssignmentDocument, String> {

    List<RoleAssignmentDocument> findByTenantIdAndScopeTypeAndScopeId(
            String tenantId, RoleScopeType scopeType, String scopeId);

    List<RoleAssignmentDocument> findByTenantIdAndSubjectTypeAndSubjectId(
            String tenantId, RoleSubjectType subjectType, String subjectId);

    Optional<RoleAssignmentDocument> findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
            String tenantId, RoleScopeType scopeType, String scopeId, RoleSubjectType subjectType, String subjectId);
}
