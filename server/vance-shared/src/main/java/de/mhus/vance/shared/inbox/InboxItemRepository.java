package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.InboxItemStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link InboxItemDocument}. Package-private —
 * callers go through {@link InboxItemService}.
 */
interface InboxItemRepository extends MongoRepository<InboxItemDocument, String> {

    Optional<InboxItemDocument> findByIdAndTenantId(String id, String tenantId);

    List<InboxItemDocument> findByTenantIdAndAssignedToUserIdAndStatus(
            String tenantId, String assignedToUserId, InboxItemStatus status);

    List<InboxItemDocument> findByTenantIdAndAssignedToUserId(
            String tenantId, String assignedToUserId);

    List<InboxItemDocument> findByTenantIdAndOriginSessionIdAndStatus(
            String tenantId, String originSessionId, InboxItemStatus status);

    long countByTenantIdAndAssignedToUserIdAndStatus(
            String tenantId, String assignedToUserId, InboxItemStatus status);
}
