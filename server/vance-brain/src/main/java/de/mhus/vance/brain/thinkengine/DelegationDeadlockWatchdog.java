package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net for the dangling-delegation pattern: a sub-process worker
 * sits in {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#BLOCKED}
 * with an empty inbox and no progress for longer than
 * {@link #staleAfter}, while its parent's
 * {@code activeDelegationWorkerId} still points at it. The worker has
 * nothing that will wake it up, the parent can't move forward — without
 * intervention the conversation is dead.
 *
 * <p>{@link de.mhus.vance.brain.ford.Ford} and
 * {@link de.mhus.vance.brain.arthur.ArthurEngine} close themselves
 * proactively when their action-loop / format-correction recovery path
 * fires (see those engines' {@code runTurnFor} finally blocks). This
 * watchdog catches the residual cases: bugs in other engines, scheduler
 * glitches, lost wakeups across pods, anything we haven't anticipated.
 *
 * <p>Action: {@link ThinkProcessService#closeProcess} the worker with
 * {@link CloseReason#STOPPED} (abnormal termination — distinct from
 * {@link CloseReason#DONE} which the engines emit on graceful recovery).
 * The close fires a status-change event, which
 * {@link ParentNotificationListener} turns into a STOPPED ProcessEvent
 * on the parent's inbox; the parent's
 * {@code reconcileWorkerLinksFromInbox} then clears the delegation
 * pointer.
 *
 * <p>Runs cluster-wide but no-ops unless the local pod holds the
 * cluster-master lease — same convention as
 * {@link de.mhus.vance.brain.cluster.SessionStaleBindSweepTick}.
 *
 * <p>Configurable via:
 * <ul>
 *   <li>{@code vance.thinkengine.deadlockWatchdog.enabled} (default {@code true})</li>
 *   <li>{@code vance.thinkengine.deadlockWatchdog.interval} (default {@code PT60S})</li>
 *   <li>{@code vance.thinkengine.deadlockWatchdog.initialDelay} (default {@code PT2M})</li>
 *   <li>{@code vance.thinkengine.deadlockWatchdog.staleAfter} (default {@code PT10M})</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name = "vance.thinkengine.deadlockWatchdog.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class DelegationDeadlockWatchdog {

    private final ClusterMasterService masterService;
    private final ThinkProcessService thinkProcessService;
    private final Duration staleAfter;

    public DelegationDeadlockWatchdog(
            ClusterMasterService masterService,
            ThinkProcessService thinkProcessService,
            @Value("${vance.thinkengine.deadlockWatchdog.staleAfter:PT10M}") Duration staleAfter) {
        this.masterService = masterService;
        this.thinkProcessService = thinkProcessService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(
            fixedDelayString = "${vance.thinkengine.deadlockWatchdog.interval:PT60S}",
            initialDelayString = "${vance.thinkengine.deadlockWatchdog.initialDelay:PT2M}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            sweep(Instant.now());
        } catch (RuntimeException e) {
            log.warn("DelegationDeadlockWatchdog sweep failed: {}", e.toString());
        }
    }

    /**
     * Pure sweep — extracted so tests can drive it deterministically.
     * Returns the number of stalled workers that were closed.
     */
    int sweep(Instant now) {
        Instant cutoff = now.minus(staleAfter);
        List<ThinkProcessDocument> stalled =
                thinkProcessService.findStalledDelegatedWorkers(cutoff);
        if (stalled.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (ThinkProcessDocument worker : stalled) {
            log.warn(
                    "Watchdog closing stalled delegated worker id='{}' parent='{}' "
                            + "tenant='{}' session='{}' updatedAt={} cutoff={}",
                    worker.getId(),
                    worker.getParentProcessId(),
                    worker.getTenantId(),
                    worker.getSessionId(),
                    worker.getUpdatedAt(),
                    cutoff);
            if (thinkProcessService.closeProcess(worker.getId(), CloseReason.STOPPED)) {
                closed++;
            }
        }
        log.info("DelegationDeadlockWatchdog swept {} stalled worker(s) (cutoff={})",
                closed, cutoff);
        return closed;
    }
}
