package de.mhus.vance.brain.notifications;

import org.springframework.stereotype.Component;

/**
 * v1 stub. v2 will look up registered FCM/APNs tokens for the user
 * and send via the appropriate platform service. Mobile-specific
 * settings (e.g. whether LOW criticality is allowed via push at
 * all) come with implementation — see spec §12.2.
 */
@Component
public class MobilePushChannel implements NotificationChannel {

    @Override
    public String name() {
        return "mobile";
    }

    @Override
    public boolean canHandle(NotifyEvent event) {
        return false;
    }

    @Override
    public DeliveryResult deliver(NotifyEvent event) {
        return DeliveryResult.skipped("mobile push channel not implemented in v1");
    }
}
