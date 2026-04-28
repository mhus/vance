package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillScope;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SkillDocument}. Package-private —
 * callers go through {@link SkillService}.
 */
interface SkillRepository extends MongoRepository<SkillDocument, String> {

    Optional<SkillDocument> findByTenantIdAndScopeAndName(
            String tenantId, SkillScope scope, String name);

    Optional<SkillDocument> findByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);

    Optional<SkillDocument> findByTenantIdAndUserIdAndName(
            String tenantId, String userId, String name);

    List<SkillDocument> findByTenantIdAndScope(String tenantId, SkillScope scope);

    List<SkillDocument> findByTenantIdAndProjectId(String tenantId, String projectId);

    List<SkillDocument> findByTenantIdAndUserId(String tenantId, String userId);
}
