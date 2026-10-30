package de.mhus.vance.shared.resourceoverride;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Package-private — callers go through {@link ResourceOverrideService}. */
interface ResourceOverrideRepository extends MongoRepository<ResourceOverrideDocument, String> {

    Optional<ResourceOverrideDocument> findByTypeAndVersion(String type, long version);

    void deleteByTypeAndVersion(String type, long version);
}
