package de.mhus.vance.shared.marvin;

import de.mhus.vance.api.marvin.NodeStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MarvinNodeDocument}. Package-private —
 * callers go through {@link MarvinNodeService}.
 */
interface MarvinNodeRepository extends MongoRepository<MarvinNodeDocument, String> {

    List<MarvinNodeDocument> findByProcessIdOrderByPositionAsc(String processId);

    List<MarvinNodeDocument> findByProcessIdAndParentIdOrderByPositionAsc(
            String processId, String parentId);

    /** Children of the root — i.e. direct children whose parentId is null. */
    List<MarvinNodeDocument> findByProcessIdAndParentIdIsNullOrderByPositionAsc(
            String processId);

    Optional<MarvinNodeDocument> findBySpawnedProcessId(String spawnedProcessId);

    Optional<MarvinNodeDocument> findByInboxItemId(String inboxItemId);

    long countByProcessIdAndStatus(String processId, NodeStatus status);

    long countByProcessId(String processId);

    void deleteByProcessId(String processId);
}
