package de.mhus.vance.brain.notifications;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of one channel's delivery attempt. Persisted to the
 * audit log ({@code notification_deliveries}).
 */
public record DeliveryResult(
        Status status,
        @Nullable String reason) {

    public enum Status {
        /** Delivered (e.g. WS frame written, email accepted by SMTP). */
        SENT,
        /** Channel didn't apply (no active connection, criticality below threshold,
         *  user has no email configured, etc.). */
        SKIPPED,
        /** Delivery attempted but failed at the transport layer. */
        FAILED
    }

    public static DeliveryResult sent() {
        return new DeliveryResult(Status.SENT, null);
    }

    public static DeliveryResult skipped(String reason) {
        return new DeliveryResult(Status.SKIPPED, reason);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(Status.FAILED, reason);
    }
}
