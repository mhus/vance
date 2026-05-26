package de.mhus.vance.shared.web;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB repository for {@link LinkPreviewCacheDocument}. Same shape
 * as {@code ImageUrlCacheRepository} — exposed publicly so the
 * preview service in {@code vance-brain} can use it directly.
 */
@Repository
public interface LinkPreviewCacheRepository
        extends MongoRepository<LinkPreviewCacheDocument, String> {

    Optional<LinkPreviewCacheDocument> findByUrl(String url);
}
