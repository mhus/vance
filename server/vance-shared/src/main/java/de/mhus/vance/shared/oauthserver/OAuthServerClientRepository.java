package de.mhus.vance.shared.oauthserver;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Outbound store for {@link OAuthServerClientDocument}. */
public interface OAuthServerClientRepository extends MongoRepository<OAuthServerClientDocument, String> {

    Optional<OAuthServerClientDocument> findByClientId(String clientId);

    List<OAuthServerClientDocument> findByLastUsedAtBefore(Instant cutoff);
}
