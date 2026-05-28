package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.eventlog.EventLogDocument;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hooks {@link ThinkProcessStatusChangedEvent} to write the terminal
 * event-log entry (COMPLETED / FAILED / CANCELLED) for scheduler-spawned
 * processes, and to notify {@link UrsaSchedulerService} so the
 * overlap-{@code QUEUE} re-fire can proceed.
 *
 * <p>Process identity is recovered through the event log itself: the
 * scheduler emits a {@code STARTED} entry on spawn that carries both
 * {@code processId} and {@code correlationId}. On termination we look
 * that entry up — when it exists, the process was scheduler-spawned and
 * we close the run with the matching correlation. When it doesn't, the
 * process is unrelated and we stay silent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerProcessTerminationListener {

    private final EventLogService eventLogService;
    private final ThinkProcessService thinkProcessService;
    private final UrsaSchedulerService schedulerService;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        Optional<EventLogDocument> startOpt = eventLogService.findStartForProcess(
                event.tenantId(), event.processId(),
                UrsaSchedulerSourceKeys.SOURCE_PREFIX);
        if (startOpt.isEmpty()) {
            return;
        }
        EventLogDocument start = startOpt.get();

        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(event.processId());
        CloseReason closeReason = processOpt
                .map(ThinkProcessDocument::getCloseReason)
                .orElse(null);
        String projectId = processOpt
                .map(ThinkProcessDocument::getProjectId)
                .orElse(start.getProjectId());

        EventType terminalType = mapTerminalType(closeReason);
        if (terminalType == null) {
            // CloseReason is CANCELLED-equivalent (stopped by scheduler itself)
            // — the cancelPrevious path already wrote the CANCELLED event,
            // so we don't duplicate.
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (closeReason != null) {
            payload.put("closeReason", closeReason.name());
        }
        eventLogService.append(
                event.tenantId(),
                projectId,
                start.getSource(),
                terminalType,
                start.getCorrelationId(),
                event.sessionId(),
                event.processId(),
                start.getRunAs(),
                payload);
        log.info("Scheduler run terminated source='{}' process='{}' closeReason={} → {}",
                start.getSource(), event.processId(), closeReason, terminalType);

        // Wake the queued-tick path if any.
        schedulerService.onProcessTerminated(event.tenantId(), projectId, event.processId());
    }

    private static @org.jspecify.annotations.Nullable EventType mapTerminalType(
            @org.jspecify.annotations.Nullable CloseReason reason) {
        if (reason == null) {
            // Should not happen for CLOSED, but if it does — treat as STOPPED
            // and surface as FAILED so the run doesn't silently vanish from
            // the log.
            return EventType.FAILED;
        }
        return switch (reason) {
            case DONE -> EventType.COMPLETED;
            case STALE -> EventType.FAILED;
            // STOPPED is ambiguous: it covers both scheduler-cancel
            // (already logged) and external/admin stops. Return null
            // when scheduler cancelled (we suppress duplicate CANCELLED
            // entry) — but since we can't tell apart cleanly without
            // an extra signal, surface as CANCELLED. Listener writes
            // CANCELLED, the cancelPrevious path also wrote CANCELLED;
            // dedup at read-time is cheaper than perfect attribution.
            case STOPPED, AUTO_CLOSE -> EventType.CANCELLED;
            case ARCHIVED, USER_DELETE, ABANDONED -> EventType.CANCELLED;
        };
    }
}
