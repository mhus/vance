package de.mhus.vance.brain.notifications;

import de.mhus.vance.shared.notifications.NotificationDeliveryDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Audit-log repository for notification deliveries. Append-only;
 * cleanup (TTL or batch deletion of old entries) is operational
 * concern outside this layer.
 */
interface NotificationDeliveryRepository
        extends MongoRepository<NotificationDeliveryDocument, String> {
}
