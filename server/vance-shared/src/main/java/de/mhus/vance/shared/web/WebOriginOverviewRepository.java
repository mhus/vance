package de.mhus.vance.shared.web;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link WebOriginOverviewDocument}.
 * Package-private — external callers go through
 * {@link WebOriginOverviewService}.
 */
interface WebOriginOverviewRepository
        extends MongoRepository<WebOriginOverviewDocument, String> {

    Optional<WebOriginOverviewDocument> findByOrigin(String origin);

    long deleteByOrigin(String origin);
}
