package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SteerMessageCodec;
import de.mhus.vance.brain.trillian.nature.SelfCheckFinding;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
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
 * Wakes Trillian user-loops whose self-check has come due.
 *
 * <p>Coarse on purpose. The goal is that a Trillian looks around
 * regularly, not that it looks at 14:03:00 — so this scans on a plain
 * fixed delay, never catches up on missed rounds, and lets drift
 * accumulate. That tolerance is what keeps the whole thing to one query
 * and no scheduler state: the due time lives on the process, so a brain
 * restart resumes the schedule rather than losing it.
 *
 * <p><b>Every pod scans, one pod fires.</b> The loop homes are podless hubs
 * (D2), so there is no owner pod to scan on behalf of — every pod sees the
 * same loops and the {@link TrillianWakeupClaimService} decides who wakes
 * one: three pods noticing the same appointment produce one turn, not
 * three. The claim keys on the due slot, so a pod that dies before waking
 * loses one round, not the appointment — the loop is still due when the
 * slot's TTL frees it again.
 */
@Component
@ConditionalOnProperty(value = "vance.trillian.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrillianHeartbeatTick {

    /** Cap per project — a sanity bound, not an expected number. */
    private static final int MAX_LOOPS_PER_PROJECT = 32;

    private final ProjectService projectService;
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
        // D2: every pod scans the podless homes (plus any legacy loop still
        // sitting in a project owned here); the wake claim decides who fires.
        List<ProjectDocument> projects = new java.util.ArrayList<>(projectService.findPodlessActive());
        for (ProjectDocument owned : projectService.findRunningByHomePodId(clusterService.selfPodId())) {
            boolean known = projects.stream()
                    .anyMatch(p -> p.getName().equals(owned.getName())
                            && p.getTenantId().equals(owned.getTenantId()));
            if (!known) {
                projects.add(owned);
            }
        }
        for (ProjectDocument project : projects) {
            for (ThinkProcessDocument loop :
                    wakeupService.loopsOf(project.getTenantId(), project.getName(), MAX_LOOPS_PER_PROJECT)) {
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
                    log.trace(
                            "Trillian heartbeat: loop id='{}' is IDLE without an "
                                    + "appointment — adopting it into the schedule",
                            loop.getId());
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
                    log.trace(
                            "Trillian heartbeat: loop id='{}' due but nothing to look at "
                                    + "— re-arming without a turn",
                            loop.getId());
                    wakeupService.arm(loop, zone);
                    continue;
                }
                // Minted here rather than inside wake(): the same id is the
                // command's idempotency key and the feed row's trace, so
                // the row and the turn it caused can be put next to each
                // other afterwards.
                // Cross-pod arbiter (D2): exactly one pod turns a due
                // appointment into a wakeup.
                Instant dueAt = wakeupService.nextWakeupAt(loop);
                if (dueAt == null || !wakeupClaimService.claim(loop.getTenantId(), loop.getId(), dueAt)) {
                    continue;
                }
                String wakeupId = "wakeup-" + loop.getId() + "-" + now.toEpochMilli();
                if (wake(loop, wakeupId, findings)) {
                    woken++;
                    // Only now: the Nature's bookkeeping is about findings
                    // that were reported, and a wakeup that did not happen
                    // must not spend a budget or end an episode.
                    recordInFeed(loop, wakeupId, findings);
                    delivered(loop, findings);
                }
            }
        }
        // Traced every round, including the empty one: the silent path is
        // the normal one, and without a line for it there is no way to
        // tell a working heartbeat from a dead one.
        log.trace(
                "Trillian heartbeat node='{}' projects={} loops={} adopted={} due={} " + "quiet={} woken={}",
                clusterService.selfNodeName(),
                projects.size(),
                loops,
                adopted,
                due,
                quiet,
                woken);
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
            List<SelfCheckFinding> findings = new java.util.ArrayList<>(agendaService.findings(loop));
            findings.addAll(natureOf(loop).selfCheckFindings(loop));
            return findings;
        } catch (RuntimeException e) {
            // A Nature that throws must not stop the heartbeat for every
            // other Trillian on this pod.
            log.warn("Trillian heartbeat: findings for loop '{}' failed: {}", loop.getId(), e.toString());
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
    private void delivered(ThinkProcessDocument loop, List<SelfCheckFinding> findings) {
        try {
            natureOf(loop).selfCheckDelivered(loop, findings);
            agendaService.delivered(loop);
        } catch (RuntimeException e) {
            log.warn(
                    "Trillian heartbeat: recording the self-check of loop '{}' failed: {}", loop.getId(), e.toString());
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

    private de.mhus.vance.brain.trillian.nature.TrillianNature natureOf(ThinkProcessDocument loop) {
        Object nature = loop.getEngineParams() == null
                ? null
                : loop.getEngineParams().get(TrillianSessionBootstrapper.PARAM_NATURE);
        return natureRegistry.resolve(nature == null ? null : nature.toString());
    }

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
