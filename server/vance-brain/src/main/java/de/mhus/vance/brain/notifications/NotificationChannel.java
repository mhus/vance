package de.mhus.vance.brain.notifications;

/**
 * Pluggable delivery surface — one bean per channel (WS, Email,
 * Mobile-Push, ...). The dispatcher iterates all registered
 * channels and delivers via every one whose {@link #canHandle}
 * returns {@code true}; channels are independent (not a fallback
 * chain) so CRITICAL items can fan out across multiple at once.
 *
 * <p>Implementations should be cheap and never throw — failures are
 * captured in the returned {@link DeliveryResult} so the dispatcher
 * can audit-log them.
 */
public interface NotificationChannel {

    /** Stable channel identifier — written to the audit log. */
    String name();

    /**
     * Decide whether to deliver. Should consider:
     * <ul>
     *   <li>connection / endpoint presence (e.g. active WS,
     *       registered email address, registered FCM token),</li>
     *   <li>criticality threshold (e.g. email skips LOW by default),</li>
     *   <li>quiet-hours (CRITICAL bypasses).</li>
     * </ul>
     */
    boolean canHandle(NotifyEvent event);

    /** Best-effort delivery; return result for audit. */
    DeliveryResult deliver(NotifyEvent event);
}
