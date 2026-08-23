package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.thinkprocess.TriggerOrigin;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hooks {@link ThinkProcessStatusChangedEvent} for scheduler-spawned
 * processes — two duties:
 *
 * <ul>
 *   <li>Terminal transitions ({@code CLOSED}) write the closing event-log
 *       entry (COMPLETED / FAILED / CANCELLED), update the scheduler-log
 *       document with the final outcome, and notify
 *       {@link UrsaSchedulerService} so the overlap-{@code QUEUE} re-fire
 *       can proceed.</li>
 *   <li>Non-terminal pauses ({@code BLOCKED}) append a timeline entry to
 *       the scheduler-log so the document doesn't forever show only
 *       "STARTED" when the engine is waiting on an inbox answer. Outcome
 *       remains {@code pending} — the run is technically still alive.</li>
 * </ul>
 *
 * <p>Run identity is read off the process itself: the spawn writes a
 * {@link TriggerOrigin} carrying kind, source and run id. A process whose
 * origin is not {@link TriggerKind#SCHEDULER} is unrelated and we stay
 * silent.
 *
 * <p>This used to be a lookup in the {@code event_log} for the process's
 * {@code STARTED} row — one Mongo query on <em>every</em> process
 * termination in the system, usually returning nothing. See
 * {@code planning/megadodo.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerProcessTerminationListener {

    private final EventLogService eventLogService;
    private final ThinkProcessService thinkProcessService;
    private final UrsaSchedulerService schedulerService;
    private final SchedulerLogService schedulerLogService;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        ThinkProcessStatus newStatus = event.newStatus();
        if (newStatus != ThinkProcessStatus.CLOSED
                && newStatus != ThinkProcessStatus.BLOCKED) {
            return;
        }
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(event.processId());
        if (processOpt.isEmpty()) {
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        TriggerOrigin origin = process.getTriggerOrigin();
        if (origin == null || origin.getKind() != TriggerKind.SCHEDULER) {
            return;
        }
        String source = origin.getSource();
        String runId = origin.getRunId();
        if (source == null || source.isBlank() || runId == null || runId.isBlank()) {
            // A scheduler spawn always carries both. Without them the run
            // cannot be attributed — say so instead of inventing an id that
            // would open a second, orphaned run log.
            log.warn("Scheduler-spawned process '{}' has an incomplete trigger origin "
                            + "(source='{}' runId='{}') — run not closed",
                    event.processId(), source, runId);
            return;
        }

        if (newStatus == ThinkProcessStatus.BLOCKED) {
            schedulerLogService.onBlocked(
                    runId, "process " + event.processId() + " awaiting inbox answer");
            log.info("Scheduler run blocked source='{}' process='{}' — awaiting inbox answer",
                    source, event.processId());
            return;
        }

        CloseReason closeReason = process.getCloseReason();
        String projectId = process.getProjectId();

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
                source,
                terminalType,
                runId,
                event.sessionId(),
                event.processId(),
                origin.getRunAs(),
                payload);
        schedulerLogService.onTerminated(
                runId, terminalType.name().toLowerCase(), java.time.Instant.now());
        log.info("Scheduler run terminated source='{}' process='{}' closeReason={} → {}",
                source, event.processId(), closeReason, terminalType);

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
            case STALE, INCOMPLETE -> EventType.FAILED;
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
