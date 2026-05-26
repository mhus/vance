package de.mhus.vance.shared.web;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB repository for {@link ImageUrlCacheDocument}. Public so
 * the validator service (in {@code vance-brain}) can use it
 * directly — there is no value in wrapping it in another service
 * layer, the cache is internal infrastructure with no business
 * rules.
 */
@Repository
public interface ImageUrlCacheRepository
        extends MongoRepository<ImageUrlCacheDocument, String> {

    Optional<ImageUrlCacheDocument> findByUrl(String url);
}
