package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.thinkprocess.TriggerOrigin;
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
 *   <li>Terminal transitions ({@code CLOSED}) close the run in the
 *       scheduler-log document and in the activity feed, and notify
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
 * termination in the system, usually returning nothing. That collection
 * is gone; see {@code planning/megadodo.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerProcessTerminationListener {

    private final ThinkProcessService thinkProcessService;
    private final UrsaSchedulerService schedulerService;
    private final SchedulerLogService schedulerLogService;
    private final de.mhus.vance.shared.megadodo.MegadodoService megadodoService;

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

        boolean completed = closeReason == CloseReason.DONE;
        schedulerLogService.onTerminated(
                runId, completed ? "completed" : "failed", java.time.Instant.now());
        megadodoService.schedulerRunFinished(
                event.tenantId(), projectId, schedulerNameOf(source), runId,
                completed,
                completed ? null : closeReasonText(closeReason),
                /*logPath*/ null);
        log.info("Scheduler run terminated source='{}' process='{}' closeReason={} completed={}",
                source, event.processId(), closeReason, completed);

        // Wake the queued-tick path if any.
        schedulerService.onProcessTerminated(event.tenantId(), projectId, event.processId());
    }

    /** {@code "ursascheduler:nightly"} → {@code "nightly"} for the feed. */
    private static String schedulerNameOf(String source) {
        return source.startsWith(UrsaSchedulerSourceKeys.SOURCE_PREFIX)
                ? source.substring(UrsaSchedulerSourceKeys.SOURCE_PREFIX.length())
                : source;
    }

    /** Why the run did not complete, in words a project owner can read. */
    private static String closeReasonText(@org.jspecify.annotations.Nullable CloseReason reason) {
        if (reason == null) return "ended without a close reason";
        return switch (reason) {
            case INCOMPLETE -> "the agent stopped before finishing";
            case STALE -> "no progress, gave up";
            case STOPPED -> "stopped";
            case AUTO_CLOSE -> "closed automatically";
            case ARCHIVED -> "archived";
            case USER_DELETE -> "deleted";
            case ABANDONED -> "abandoned";
            case DONE -> "done";
        };
    }

}
