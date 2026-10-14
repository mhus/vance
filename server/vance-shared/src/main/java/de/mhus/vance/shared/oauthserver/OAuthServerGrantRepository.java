package de.mhus.vance.shared.oauthserver;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Outbound store for {@link OAuthServerGrantDocument}. */
public interface OAuthServerGrantRepository extends MongoRepository<OAuthServerGrantDocument, String> {

    Optional<OAuthServerGrantDocument> findByGrantId(String grantId);

    List<OAuthServerGrantDocument> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId);

    List<OAuthServerGrantDocument> findByTenantIdAndUserIdAndClientIdAndRevokedAtIsNull(
            String tenantId, String userId, String clientId);

    boolean existsByClientIdAndRevokedAtIsNull(String clientId);
}
