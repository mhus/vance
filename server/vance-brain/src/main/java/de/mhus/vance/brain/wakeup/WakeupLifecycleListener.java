package de.mhus.vance.brain.wakeup;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cancels all wakeups for a process when it transitions to {@link
 * ThinkProcessStatus#CLOSED} (the single terminal state — see
 * {@code ParentNotificationListener#mapStatus}). PAUSED / SUSPENDED
 * leave wakeups in place: the lifecycle matrix in
 * {@code planning/wakeup-and-exec.md} §6 specifies wall-clock
 * semantics, so the event still queues into the inbox and is drained
 * on resume.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WakeupLifecycleListener {

    private final WakeupRegistry wakeupRegistry;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        if (event.priorStatus() == ThinkProcessStatus.CLOSED) {
            return;
        }
        wakeupRegistry.cancelAll(event.processId());
    }
}
