package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes Trillian user-loops whose self-check has come due.
 *
 * <p>Coarse on purpose. The goal is that a Trillian looks around
 * regularly, not that it looks at 14:03:00 — so this scans on a plain
 * fixed delay, never catches up on missed rounds, and lets drift
 * accumulate. That tolerance is what keeps the whole thing to one query
 * and no scheduler state: the due time lives on the process, so a brain
 * restart resumes the schedule rather than losing it.
 *
 * <p><b>One pod per project.</b> Projects are assigned to pods, and the
 * loop only runs where its project lives. Scanning
 * {@code findRunningByHomeNode} rather than all projects is therefore not
 * an optimisation but the correctness condition: three pods each waking
 * the same Trillian would give it three turns for one appointment.
 *
 * <p>Podless projects are skipped, and Trillians are not created in them
 * (see {@code TrillianSessionBootstrapper}) — a project with no home pod
 * moves between pods on reconnect, which is no ground for something that
 * is supposed to sit still and keep watch.
 */
@Component
@ConditionalOnProperty(value = "vance.trillian.heartbeat.enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrillianHeartbeatTick {

    /** Cap per project — a sanity bound, not an expected number. */
    private static final int MAX_LOOPS_PER_PROJECT = 32;

    private final ProjectService projectService;
    private final ClusterService clusterService;
    private final ThinkProcessService thinkProcessService;
    private final TrillianWakeupService wakeupService;
    private final ProcessEventEmitter eventEmitter;

    @Scheduled(fixedDelayString = "${vance.trillian.heartbeat.intervalMs:60000}",
            initialDelayString = "${vance.trillian.heartbeat.intervalMs:60000}")
    public void tick() {
        String node = clusterService.selfNodeName();
        if (node == null || node.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        int woken = 0;
        for (ProjectDocument project : projectService.findRunningByHomeNode(node)) {
            for (ThinkProcessDocument loop : wakeupService.loopsOf(
                    project.getTenantId(), project.getName(), MAX_LOOPS_PER_PROJECT)) {
                if (loop.getStatus() != ThinkProcessStatus.IDLE
                        || !wakeupService.isDue(loop, now)) {
                    continue;
                }
                if (wake(loop)) {
                    woken++;
                }
            }
        }
        if (woken > 0) {
            log.debug("Trillian heartbeat woke {} loop(s) on node '{}'", woken, node);
        }
    }

    /**
     * Hands the loop a self-check and clears the due marker.
     *
     * <p>The marker is cleared first: a wakeup that fails to schedule
     * should cost one round, not turn into a tight loop of retries on
     * every tick. The next arming happens at the loop's own yield point.
     */
    private boolean wake(ThinkProcessDocument loop) {
        try {
            wakeupService.disarm(loop);
            SteerMessage.ExternalCommand check = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ "wakeup-" + loop.getId() + "-" + Instant.now().toEpochMilli(),
                    TrillianWakeupService.COMMAND_SELF_CHECK,
                    Map.of());
            if (!thinkProcessService.appendPending(
                    loop.getId(), SteerMessageCodec.toDocument(check))) {
                return false;
            }
            eventEmitter.scheduleTurn(loop.getId());
            log.info("Trillian self-check due — waking loop id='{}'", loop.getId());
            return true;
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: could not wake loop '{}': {}",
                    loop.getId(), e.toString());
            return false;
        }
    }
}
