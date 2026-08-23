package de.mhus.vance.brain.notifications;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.shared.inbox.MaximegalonCreatedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDelegatedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Routes inbox events to all registered notification channels.
 *
 * <p>Subscribes to {@link MaximegalonCreatedEvent} (notify the
 * assignee) and {@link MaximegalonDelegatedEvent} (notify the new
 * assignee). Auto-answered LOW items skip notification — there's
 * nothing for the user to do about them at create-time.
 *
 * <p>Channels are tried in their bean-registration order; each runs
 * {@link NotificationChannel#canHandle} and, if applicable,
 * {@link NotificationChannel#deliver}. Results land in
 * {@link NotificationDeliveryService#log} for audit.
 */
@Service
@Slf4j
public class NotificationDispatcher {

    private final List<NotificationChannel> channels;
    private final NotificationDeliveryService deliveryLog;

    public NotificationDispatcher(
            List<NotificationChannel> channelBeans,
            NotificationDeliveryService deliveryLog) {
        this.channels = List.copyOf(channelBeans);
        this.deliveryLog = deliveryLog;
        log.info("NotificationDispatcher: {} channel(s) registered: {}",
                channels.size(), channels.stream().map(NotificationChannel::name).toList());
    }

    @EventListener
    public void onCreated(MaximegalonCreatedEvent event) {
        MaximegalonDocument item = event.item();
        if (item.getStatus() != MaximegalonStatus.PENDING) {
            // Auto-answered (LOW) at create-time → don't ping anyone.
            return;
        }
        notify(toEvent(item, item.getAssignedToUserId()));
    }

    @EventListener
    public void onDelegated(MaximegalonDelegatedEvent event) {
        MaximegalonDocument item = event.item();
        if (item.getStatus() != MaximegalonStatus.PENDING) {
            return;
        }
        notify(toEvent(item, item.getAssignedToUserId()));
    }

    /** Public entry point for direct callers (rarely needed; events preferred). */
    public void notify(NotifyEvent event) {
        for (NotificationChannel ch : channels) {
            DeliveryResult result;
            try {
                if (!ch.canHandle(event)) {
                    result = DeliveryResult.skipped("canHandle=false");
                } else {
                    result = ch.deliver(event);
                }
            } catch (RuntimeException re) {
                result = DeliveryResult.failed("Exception: " + re.toString());
                log.warn("NotificationChannel '{}' threw for item='{}': {}",
                        ch.name(), event.inboxItemId(), re.toString());
            }
            deliveryLog.log(event, ch.name(), result, Instant.now());
        }
    }

    private static NotifyEvent toEvent(MaximegalonDocument item, String recipientUserId) {
        return new NotifyEvent(
                item.getTenantId(),
                recipientUserId,
                item.getId() == null ? "" : item.getId(),
                item.getCriticality(),
                item.getTitle(),
                item.getBody(),
                /*deepLink*/ null);
    }
}
