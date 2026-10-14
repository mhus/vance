package de.mhus.vance.shared.oauthserver;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Outbound store for {@link OAuthServerAuthRequestDocument}. */
public interface OAuthServerAuthRequestRepository extends MongoRepository<OAuthServerAuthRequestDocument, String> {

    Optional<OAuthServerAuthRequestDocument> findByRequestId(String requestId);

    void deleteByRequestId(String requestId);
}
