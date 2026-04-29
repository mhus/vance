package de.mhus.vance.shared.servertool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ServerToolDocument}. Public so the
 * cascade-aware service in {@code vance-brain} can read it; project
 * services should still go through that service for write paths.
 */
public interface ServerToolRepository extends MongoRepository<ServerToolDocument, String> {

    Optional<ServerToolDocument> findByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);

    boolean existsByTenantIdAndProjectIdAndName(
            String tenantId, String projectId, String name);

    List<ServerToolDocument> findByTenantIdAndProjectId(
            String tenantId, String projectId);

    List<ServerToolDocument> findByTenantIdAndProjectIdAndEnabled(
            String tenantId, String projectId, boolean enabled);
}
