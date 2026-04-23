package de.mhus.vance.shared.storage;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link StorageData} chunks. Package-private — accessed only
 * from {@link MongoStorageService} and the chunked streams.
 */
interface StorageDataRepository extends MongoRepository<StorageData, String> {

    @Nullable StorageData findByUuidAndIndex(String uuid, int index);

    @Nullable StorageData findByUuidAndIsFinalTrue(String uuid);

    void deleteByUuid(String uuid);

    long countByUuid(String uuid);

    List<StorageData> findAllByUuidAndIsFinalTrue(String uuid);

    List<StorageData> findAllByUuidAndIndex(String uuid, int index);

    List<StorageData> findAllByUuid(String uuid);
}
