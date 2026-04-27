package de.mhus.vance.brain.notifications;

import org.springframework.stereotype.Component;

/**
 * v1 stub. Architecture-only: the bean exists so future
 * implementations slot in without dispatcher changes. Always
 * skips delivery in v1.
 *
 * <p>v2 will read {@code notify.email.address} (cascade
 * tenant→project→user), compare {@code criticality} against
 * {@code notify.email.minCriticality}, respect quiet-hours,
 * and queue messages with batching for non-CRITICAL.
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean canHandle(NotifyEvent event) {
        return false;
    }

    @Override
    public DeliveryResult deliver(NotifyEvent event) {
        return DeliveryResult.skipped("email channel not implemented in v1");
    }
}
