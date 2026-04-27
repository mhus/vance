package de.mhus.vance.brain.notifications;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Persists per-channel delivery attempts. Append-only audit log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private final NotificationDeliveryRepository repository;

    public void log(NotifyEvent event, String channel, DeliveryResult result, Instant when) {
        try {
            NotificationDeliveryDocument entry = NotificationDeliveryDocument.builder()
                    .tenantId(event.tenantId())
                    .userId(event.userId())
                    .inboxItemId(event.inboxItemId())
                    .channel(channel)
                    .status(result.status().name())
                    .reason(result.reason())
                    .createdAt(when)
                    .build();
            repository.save(entry);
        } catch (RuntimeException re) {
            // Audit-log failure should never break the delivery path.
            log.warn("Delivery-log write failed (item='{}' channel='{}'): {}",
                    event.inboxItemId(), channel, re.toString());
        }
    }
}
