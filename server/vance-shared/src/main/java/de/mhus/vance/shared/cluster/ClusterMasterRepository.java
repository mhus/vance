package de.mhus.vance.shared.cluster;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link ClusterMasterDocument}. */
public interface ClusterMasterRepository extends MongoRepository<ClusterMasterDocument, String> {
}
