package de.mhus.vance.shared.oauth;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo repository for {@link OAuthStateDocument}. The application
 * layer ({@code OAuthStateService}) wraps this — controllers do not
 * touch the repository directly.
 */
public interface OAuthStateRepository extends MongoRepository<OAuthStateDocument, String> {

    Optional<OAuthStateDocument> findByState(String state);

    long deleteByState(String state);
}
