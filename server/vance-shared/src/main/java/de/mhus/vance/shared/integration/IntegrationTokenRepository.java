package de.mhus.vance.shared.integration;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Outbound store for {@link IntegrationTokenDocument}. */
public interface IntegrationTokenRepository extends MongoRepository<IntegrationTokenDocument, String> {

    Optional<IntegrationTokenDocument> findByTokenId(String tokenId);

    List<IntegrationTokenDocument> findByTenantIdAndUserIdOrderByCreatedAtDesc(
            String tenantId, String userId);
}
