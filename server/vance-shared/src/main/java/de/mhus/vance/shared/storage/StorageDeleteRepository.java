package de.mhus.vance.shared.storage;

import java.util.Date;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link StorageDelete} markers. Package-private.
 */
interface StorageDeleteRepository extends MongoRepository<StorageDelete, String> {

    List<StorageDelete> findByDeletedAtLessThanEqual(Date timestamp);
}
