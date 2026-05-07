package de.mhus.vance.shared.cluster;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link BrainPodDocument}. Package-private —
 * external callers go through {@link BrainPodService}.
 */
interface BrainPodRepository extends MongoRepository<BrainPodDocument, String> {

    Optional<BrainPodDocument> findByPodId(String podId);

    Optional<BrainPodDocument> findByClusterIdAndNodeName(String clusterId, String nodeName);

    List<BrainPodDocument> findByClusterId(String clusterId);

    boolean existsByClusterIdAndNodeName(String clusterId, String nodeName);

    long deleteByPodId(String podId);
}
