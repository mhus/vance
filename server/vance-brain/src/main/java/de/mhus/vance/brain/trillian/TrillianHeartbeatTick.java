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
import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import java.util.List;
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
    private final de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry;

    @Scheduled(fixedDelayString = "${vance.trillian.heartbeat.intervalMs:60000}",
            initialDelayString = "${vance.trillian.heartbeat.intervalMs:60000}")
    public void tick() {
        String node = clusterService.selfNodeName();
        if (node == null || node.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        int loops = 0;
        int due = 0;
        int adopted = 0;
        int quiet = 0;
        int woken = 0;
        List<ProjectDocument> projects = projectService.findRunningByHomeNode(node);
        for (ProjectDocument project : projects) {
            for (ThinkProcessDocument loop : wakeupService.loopsOf(
                    project.getTenantId(), project.getName(), MAX_LOOPS_PER_PROJECT)) {
                loops++;
                if (loop.getStatus() != ThinkProcessStatus.IDLE) {
                    continue;
                }
                // An IDLE loop with no appointment has fallen out of the
                // schedule and cannot get back in on its own: arming
                // happens at the loop's yield point, and it will not yield
                // again until something wakes it. That happens whenever
                // the world changed after the last yield — a worker that
                // was RUNNING (and therefore suppressed the alarm) parked
                // itself, say. Adopting it here is what keeps the watcher
                // watched; arm() still refuses while a worker is running.
                if (!wakeupService.isArmed(loop)) {
                    adopted++;
                    log.trace("Trillian heartbeat: loop id='{}' is IDLE without an "
                            + "appointment — adopting it into the schedule", loop.getId());
                    wakeupService.arm(loop, zone);
                    continue;
                }
                if (!wakeupService.isDue(loop, now)) {
                    continue;
                }
                due++;
                // Ask the Nature what it sees *before* spending a turn.
                // Nothing to look at means the wakeup costs one query and
                // no tokens — which is what makes an hourly rhythm
                // affordable at all.
                List<SelfCheckFinding> findings = findingsOf(loop);
                if (findings.isEmpty()) {
                    quiet++;
                    log.trace("Trillian heartbeat: loop id='{}' due but nothing to look at "
                            + "— re-arming without a turn", loop.getId());
                    wakeupService.arm(loop, zone);
                    continue;
                }
                if (wake(loop, findings)) {
                    woken++;
                }
            }
        }
        // Traced every round, including the empty one: the silent path is
        // the normal one, and without a line for it there is no way to
        // tell a working heartbeat from a dead one.
        log.trace("Trillian heartbeat node='{}' projects={} loops={} adopted={} due={} "
                        + "quiet={} woken={}",
                node, projects.size(), loops, adopted, due, quiet, woken);
    }

    /**
     * Hands the loop a self-check and clears the due marker.
     *
     * <p>The marker is cleared first: a wakeup that fails to schedule
     * should cost one round, not turn into a tight loop of retries on
     * every tick. The next arming happens at the loop's own yield point.
     */
    private List<SelfCheckFinding> findingsOf(ThinkProcessDocument loop) {
        try {
            Object nature = loop.getEngineParams() == null ? null
                    : loop.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE);
            return natureRegistry.resolve(nature == null ? null : nature.toString())
                    .selfCheckFindings(loop);
        } catch (RuntimeException e) {
            // A Nature that throws must not stop the heartbeat for every
            // other Trillian on this pod.
            log.warn("Trillian heartbeat: findings for loop '{}' failed: {}",
                    loop.getId(), e.toString());
            return List.of();
        }
    }

    private boolean wake(ThinkProcessDocument loop, List<SelfCheckFinding> findings) {
        try {
            wakeupService.disarm(loop);
            SteerMessage.ExternalCommand check = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ "wakeup-" + loop.getId() + "-" + Instant.now().toEpochMilli(),
                    TrillianWakeupService.COMMAND_SELF_CHECK,
                    Map.of(TrillianWakeupService.PARAM_FINDINGS,
                            findings.stream().map(SelfCheckFinding::render).toList()));
            if (!thinkProcessService.appendPending(
                    loop.getId(), SteerMessageCodec.toDocument(check))) {
                return false;
            }
            eventEmitter.scheduleTurn(loop.getId());
            log.info("Trillian self-check: waking loop id='{}' with {} finding(s)",
                    loop.getId(), findings.size());
            return true;
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: could not wake loop '{}': {}",
                    loop.getId(), e.toString());
            return false;
        }
    }
}
