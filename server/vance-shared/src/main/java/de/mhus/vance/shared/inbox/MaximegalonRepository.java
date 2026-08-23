package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MaximegalonDocument}. Package-private —
 * callers go through {@link MaximegalonService}.
 */
interface MaximegalonRepository extends MongoRepository<MaximegalonDocument, String> {

    Optional<MaximegalonDocument> findByIdAndTenantId(String id, String tenantId);

    List<MaximegalonDocument> findByTenantIdAndAssignedToUserIdAndStatus(
            String tenantId, String assignedToUserId, MaximegalonStatus status);

    List<MaximegalonDocument> findByTenantIdAndAssignedToUserId(
            String tenantId, String assignedToUserId);

    List<MaximegalonDocument> findByTenantIdAndOriginSessionIdAndStatus(
            String tenantId, String originSessionId, MaximegalonStatus status);

    long countByTenantIdAndAssignedToUserIdAndStatus(
            String tenantId, String assignedToUserId, MaximegalonStatus status);
}
