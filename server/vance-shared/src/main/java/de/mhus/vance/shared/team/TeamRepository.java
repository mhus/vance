package de.mhus.vance.shared.team;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link TeamDocument}. Package-private — callers go
 * through {@link TeamService}.
 */
interface TeamRepository extends MongoRepository<TeamDocument, String> {

    Optional<TeamDocument> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantIdAndName(String tenantId, String name);

    List<TeamDocument> findByTenantId(String tenantId);

    List<TeamDocument> findByTenantIdAndMembersContaining(String tenantId, String username);
}
