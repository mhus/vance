package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes Trillian user-loops whose self-check or whose schedule has come due.
 *
 * <p>Coarse on purpose. The goal is that a Trillian looks around
 * regularly, not that it looks at 14:03:00 — so this scans on a plain
 * fixed delay, never catches up on missed rounds, and lets drift
 * accumulate. That tolerance is what keeps the whole thing to one query
 * and no scheduler state: the due times live on the process, so a brain
 * restart resumes the schedule rather than losing it.
 *
 * <p><b>Two clocks, one wake.</b> The self-check ladder (10/20/40/60 min,
 * measured in silence) decides when the loop looks around; the schedule
 * marker (earliest enabled appointment in the home, A4) decides when an
 * appointment is up. Appointments are independent of the ladder: they fire
 * on the scan grid, also while a worker runs and the ladder is disarmed — a
 * 09:00 stand-up must not wait for the loop's next look around.
 *
 * <p><b>Every pod scans, one pod fires.</b> The loop homes are podless hubs
 * (D2), so there is no owner pod to scan on behalf of — every pod reads the
 * live loops with one indexed query, and the {@link TrillianWakeupClaimService}
 * decides who wakes one: three pods noticing the same appointment produce one
 * turn, not three. The claim keys on the due slot, so a pod that dies before
 * waking loses that slot for the claim TTL (an hour), not the appointment.
 */
@Component
@ConditionalOnProperty(value = "vance.trillian.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrillianHeartbeatTick {

    /** Sanity bound on live loops per scan — not an expected number. */
    private static final int MAX_LOOPS = 1000;

    private final ClusterService clusterService;
    private final ThinkProcessService thinkProcessService;
    private final TrillianWakeupService wakeupService;
    private final TrillianWakeupClaimService wakeupClaimService;
    private final TrillianAgendaService agendaService;
    private final ProcessEventEmitter eventEmitter;
    private final de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry natureRegistry;
    private final MegadodoService megadodoService;

    @Scheduled(
            fixedDelayString = "${vance.trillian.heartbeat.interval-ms:60000}",
            initialDelayString = "${vance.trillian.heartbeat.interval-ms:60000}")
    public void tick() {
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        int loops = 0;
        int due = 0;
        int adopted = 0;
        int quiet = 0;
        int woken = 0;
        for (ThinkProcessDocument loop : wakeupService.liveLoops(MAX_LOOPS)) {
            loops++;
            // Overlap-skip: a loop that is mid-turn sees its inbox when the
            // turn drains; a wakeup now would be a second turn for nothing.
            if (loop.getStatus() != ThinkProcessStatus.IDLE) {
                continue;
            }
            // A loop whose schedule marker was never computed (fresh loop,
            // rebuilt loop) gets it now — one listing of its home, once.
            if (!wakeupService.hasScheduleMarker(loop)) {
                agendaService.refreshScheduleMarker(loop);
                loop = thinkProcessService.findById(loop.getId()).orElse(loop);
            }
            boolean scheduleDue = wakeupService.isScheduleDue(loop, now);
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
                log.trace(
                        "Trillian heartbeat: loop id='{}' is IDLE without an "
                                + "appointment — adopting it into the schedule",
                        loop.getId());
                wakeupService.arm(loop, zone);
                if (!scheduleDue) {
                    continue;
                }
            }
            boolean ladderDue = wakeupService.isDue(loop, now);
            if (!ladderDue && !scheduleDue) {
                continue;
            }
            due++;
            TrillianNature nature = natureOf(loop);
            // Ask what there is to see *before* spending a turn. Nothing to
            // look at means the wakeup costs a query and no tokens — which
            // is what makes an hourly rhythm affordable at all. A pure
            // appointment wake carries only the appointments.
            List<SelfCheckFinding> findings = ladderDue ? findingsOf(loop, nature) : scheduleFindingsOf(loop);
            if (ladderDue) {
                // The ladder wake is also where hand-edited schedule
                // documents are noticed: the marker is recomputed here.
                agendaService.refreshScheduleMarker(loop);
            }
            if (findings.isEmpty()) {
                quiet++;
                log.trace(
                        "Trillian heartbeat: loop id='{}' due but nothing to look at " + "— re-arming without a turn",
                        loop.getId());
                if (ladderDue) {
                    wakeupService.arm(loop, zone);
                } else {
                    // The marker said due, the documents disagree (edited
                    // or removed meanwhile): recompute instead of retrying.
                    agendaService.refreshScheduleMarker(loop);
                }
                continue;
            }
            // Cross-pod arbiter (D2): exactly one pod turns a due
            // appointment into a wakeup.
            Instant slotAt = ladderDue ? wakeupService.nextWakeupAt(loop) : wakeupService.scheduleMarker(loop);
            String slot = (ladderDue ? "w" : "s") + (slotAt == null ? now.toEpochMilli() : slotAt.toEpochMilli());
            if (!wakeupClaimService.claim(loop.getTenantId(), loop.getId(), slot)) {
                continue;
            }
            // Minted here rather than inside wake(): the same id is the
            // command's idempotency key and the feed row's trace, so
            // the row and the turn it caused can be put next to each
            // other afterwards.
            String wakeupId = "wakeup-" + loop.getId() + "-" + now.toEpochMilli();
            if (wake(loop, wakeupId, findings)) {
                woken++;
                // Only now: the bookkeeping is about findings that were
                // reported, and a wakeup that did not happen must not spend
                // a budget, end an episode or advance an appointment.
                recordInFeed(loop, wakeupId, findings);
                delivered(loop, nature, findings);
            }
        }
        // Traced every round, including the empty one: the silent path is
        // the normal one, and without a line for it there is no way to
        // tell a working heartbeat from a dead one.
        log.trace(
                "Trillian heartbeat node='{}' loops={} adopted={} due={} quiet={} woken={}",
                clusterService.selfNodeName(),
                loops,
                adopted,
                due,
                quiet,
                woken);
    }

    /**
     * Everything the loop could be woken for on a ladder wake: the home's
     * appointments (and {@code [bored]} when the Nature opted in), then the
     * Nature's own findings.
     */
    private List<SelfCheckFinding> findingsOf(ThinkProcessDocument loop, TrillianNature nature) {
        try {
            List<SelfCheckFinding> findings =
                    new java.util.ArrayList<>(agendaService.findings(loop, nature.acceptsBoredFindings()));
            findings.addAll(nature.selfCheckFindings(loop));
            return findings;
        } catch (RuntimeException e) {
            // A Nature that throws must not stop the heartbeat for every
            // other Trillian on this pod.
            log.warn("Trillian heartbeat: findings for loop '{}' failed: {}", loop.getId(), e.toString());
            return List.of();
        }
    }

    /** Only the due appointments — the content of a schedule wake. */
    private List<SelfCheckFinding> scheduleFindingsOf(ThinkProcessDocument loop) {
        try {
            return agendaService.scheduleFindings(loop);
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: schedules of loop '{}' failed: {}", loop.getId(), e.toString());
            return List.of();
        }
    }

    /**
     * Tells the Nature its findings were delivered, so it can write down
     * whatever reporting them costs.
     *
     * <p>After the wakeup, and never instead of it: the loop already has
     * the self-check in its inbox, so a Nature that fails here leaves a
     * budget unspent, which is the harmless direction.
     */
    private void delivered(ThinkProcessDocument loop, TrillianNature nature, List<SelfCheckFinding> findings) {
        try {
            nature.selfCheckDelivered(loop, findings);
        } catch (RuntimeException e) {
            log.warn(
                    "Trillian heartbeat: recording the self-check of loop '{}' failed: {}", loop.getId(), e.toString());
        }
        try {
            agendaService.delivered(loop, findings);
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: advancing the schedules of loop '{}' failed: {}", loop.getId(), e.toString());
        }
    }

    /**
     * Writes the wakeup into the project feed.
     *
     * <p>Here and not in {@link #wake}, for the same reason
     * {@link #delivered} sits here: {@code wake} reports whether the loop
     * was actually woken, and a feed write that throws must not turn a
     * wakeup that landed into one that reads as failed. The feed row is
     * the only place a reader can later see <em>why</em> a Trillian
     * started working at four in the morning — the ladder itself leaves
     * no trace, and the self-check command is consumed by the turn.
     */
    private void recordInFeed(ThinkProcessDocument loop, String wakeupId, List<SelfCheckFinding> findings) {
        try {
            megadodoService.trillianWokeUp(
                    loop.getTenantId(),
                    loop.getProjectId(),
                    loop.getId(),
                    trillianNameOf(loop),
                    wakeupId,
                    findings.stream().map(SelfCheckFinding::summary).toList());
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: feed row for loop '{}' failed: {}", loop.getId(), e.toString());
        }
    }

    /**
     * The {@code _trillian-*} service account the loop runs as — the actor
     * of the row, the way {@code runAs} is the actor of a scheduler run.
     */
    private @Nullable String trillianNameOf(ThinkProcessDocument loop) {
        Object name = loop.getEngineParams() == null
                ? null
                : loop.getEngineParams().get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return name == null ? null : name.toString();
    }

    private TrillianNature natureOf(ThinkProcessDocument loop) {
        Object nature = loop.getEngineParams() == null
                ? null
                : loop.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE);
        return natureRegistry.resolve(nature == null ? null : nature.toString());
    }

    /**
     * Hands the loop a self-check and clears the due marker.
     *
     * <p>The marker is cleared first: a wakeup that fails to schedule
     * should cost one round, not turn into a tight loop of retries on
     * every tick. The next arming happens at the loop's own yield point.
     */
    private boolean wake(ThinkProcessDocument loop, String wakeupId, List<SelfCheckFinding> findings) {
        try {
            wakeupService.disarm(loop);
            SteerMessage.ExternalCommand check = new SteerMessage.ExternalCommand(
                    Instant.now(),
                    /*idempotencyKey*/ wakeupId,
                    TrillianWakeupService.COMMAND_SELF_CHECK,
                    Map.of(
                            TrillianWakeupService.PARAM_FINDINGS,
                            findings.stream().map(SelfCheckFinding::render).toList()));
            if (!thinkProcessService.appendPending(loop.getId(), SteerMessageCodec.toDocument(check))) {
                return false;
            }
            eventEmitter.scheduleTurn(loop.getId());
            log.info("Trillian self-check: waking loop id='{}' with {} finding(s)", loop.getId(), findings.size());
            return true;
        } catch (RuntimeException e) {
            log.warn("Trillian heartbeat: could not wake loop '{}': {}", loop.getId(), e.toString());
            return false;
        }
    }
}
