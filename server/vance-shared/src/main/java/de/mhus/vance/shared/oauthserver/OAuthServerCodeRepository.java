package de.mhus.vance.shared.oauthserver;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Outbound store for {@link OAuthServerCodeDocument}. */
public interface OAuthServerCodeRepository extends MongoRepository<OAuthServerCodeDocument, String> {

    Optional<OAuthServerCodeDocument> findByCodeHash(String codeHash);
}
