package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Maps life-cycle status transitions of a child process to a
 * {@code PROCESS_EVENT} on its parent's pending queue. The actual
 * appending + lane-wakeup is delegated to {@link ProcessEventEmitter}
 * so this class stays focused on the filter rules.
 *
 * <p>Filter:
 * <ul>
 *   <li>Skip top-level processes (no {@code parentProcessId}).</li>
 *   <li>Skip status repaints — same prior and new status.</li>
 *   <li>Skip intermediate transitions ({@code READY/RUNNING/PAUSED/SUSPENDED})
 *       — they're internal lane state, not something the parent needs
 *       to be woken for.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParentNotificationListener {

    private final ProcessEventEmitter eventEmitter;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        String parentId = event.parentProcessId();
        if (parentId == null) {
            return;
        }
        if (event.priorStatus() == event.newStatus()) {
            return;
        }
        ProcessEventType eventType = mapStatus(event.newStatus());
        if (eventType == null) {
            return;
        }
        boolean queued = eventEmitter.notifyParent(
                parentId,
                event.processId(),
                eventType,
                humanSummary(event.processId(), event.newStatus()),
                /*payload*/ null);
        if (queued) {
            log.info("Parent notify queued parent='{}' child='{}' event={}",
                    parentId, event.processId(), eventType);
        }
    }

    private static @Nullable ProcessEventType mapStatus(ThinkProcessStatus status) {
        return switch (status) {
            case DONE -> ProcessEventType.DONE;
            case BLOCKED -> ProcessEventType.BLOCKED;
            case STOPPED -> ProcessEventType.STOPPED;
            case STALE -> ProcessEventType.FAILED;
            case READY, RUNNING, PAUSED, SUSPENDED -> null;
        };
    }

    private static String humanSummary(String childProcessId, ThinkProcessStatus status) {
        return "Child process " + childProcessId + " status=" + status.name().toLowerCase();
    }
}
