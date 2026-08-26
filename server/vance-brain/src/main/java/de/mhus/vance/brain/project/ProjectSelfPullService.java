package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Self-Pull: this pod goes looking for projects that need an owner and that
 * nobody holds, and takes the ones it is eligible for and has room for.
 *
 * <p>Two callers, one loop. The boot pull ({@code ProjectStartupReclaimer}) and
 * the optional periodic tick below ask the same question at different times, so
 * the loop lives here rather than in a class whose name says "startup".
 *
 * <h2>Self-Pull is greedy-local, the distributor is balance-aware</h2>
 * This is the honest limit of the mechanism and the reason the periodic variant
 * is <b>off by default</b>. A self-pull can only ask "may I, and do I have
 * room" — it knows nothing about the other pods, so it cannot answer "who
 * <em>should</em> get this", which is what {@code ProjectPlacementService}
 * answers for every other path. Run continuously on every pod, it converges to
 * "the emptiest pod takes everything up to its cap" and makes the distributor's
 * load balancing decorative.
 *
 * <p>At boot that trade is clearly worth it: the pod has just come up, projects
 * have just lost their holder (single-pod restart and k8s rolling restart are
 * the same event), and without it they stay dark until the next distributor
 * tick — or forever when the master role is disabled. As a steady-state
 * mechanism it is a second, continuously running placement authority whose rule
 * contradicts the first one, which is exactly what
 * {@code planning/project-placement-labels.md} §1 set out to remove. Hence: a
 * knob, defaulting to off.
 *
 * <p>What the periodic pull buys, precisely, is the recovery of projects whose
 * only reason to run is <em>waiting background work</em> (schedulers, hooks —
 * the derived {@code ownerRequired}) after a peer pod died, in a cluster where
 * the master role is off. Everything else has somebody asking for it: a
 * session, a workspace read, an event trigger, a create.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectSelfPullService {

    /**
     * Page size for the candidate query. Matches the distributor's appetite —
     * small enough to re-query between pages without much waste.
     */
    private static final int PAGE_SIZE = 20;

    private final ProjectService projectService;
    private final ProjectLifecycleService lifecycleService;
    private final ClusterService clusterService;
    private final ClusterProperties clusterProperties;
    private final ProjectPlacementService placementService;

    /**
     * Periodic self-pull. The tick fires on the configured interval regardless
     * and returns immediately unless {@code vance.cluster.self-pull.scheduled}
     * is on — same shape as {@code ClusterDistributorTick}, which fires
     * everywhere and no-ops unless the pod is master. A conditional
     * {@code @Scheduled} would have to be a separate bean, and Spring rejects a
     * zero {@code fixedDelay}, so the gate belongs in the body.
     *
     * <p><b>Known cost when enabled:</b> every pod queries the same candidate
     * set on the same cadence. The CAS in {@code ProjectService.claim} makes
     * that safe, not cheap; {@code fixedDelay} lets the pods drift apart over
     * time, but there is no jitter. Fine for an experiment, worth revisiting
     * before it becomes a default.
     */
    @Scheduled(fixedDelayString = "${vance.cluster.self-pull.interval:PT5M}",
            initialDelayString = "${vance.cluster.self-pull.interval:PT5M}")
    public void tick() {
        if (!clusterProperties.getSelfPull().isScheduled()) {
            return;
        }
        try {
            pullOnce("scheduled");
        } catch (RuntimeException e) {
            log.warn("ProjectSelfPullService: scheduled round failed: {}", e.toString());
        }
    }

    /**
     * One self-pull pass. Brings every project that needs an owner, holds no
     * live lease, is eligible here and fits the per-run cap.
     *
     * <p>The cap is {@code min(budget + buffer, localHeadroom)}:
     * <ul>
     *   <li>{@code budget} is {@code vance.cluster.resources.startupScore} —
     *       how much this pod grabs in one pass. A 50% buffer lets the last
     *       candidate tip over the line so an above-average project does not
     *       get stuck waiting for the distributor.</li>
     *   <li>{@code localHeadroom} is what is left before {@code maxScore}. It
     *       is the reason the same budget is safe on a periodic run: as the pod
     *       fills, the headroom shrinks and the cap tightens with it.</li>
     * </ul>
     *
     * <p>Headroom is read <em>once</em>, not per candidate:
     * {@code resourcesCurrentScore} only changes on the heartbeat, so
     * re-reading it inside the loop would return the same stale number at the
     * cost of a query each time. The running total is the accurate part.
     *
     * <p>Candidate set is "needs an owner and holds no live lease" — the derived
     * {@code ownerRequired} for {@code AUTO} projects plus anything pinned to
     * {@code PERMANENT}, and only for statuses that express the intent to run. A
     * {@code SUSPENDED} project is deliberately not pulled: {@code bring} would
     * take it straight back to RUNNING, so a suspend would expire together with
     * the holder's lease and restart the very scheduler it was meant to stop.
     *
     * @param reason appears in the log line so a boot pass and a periodic pass
     *     are distinguishable in a running system
     */
    public void pullOnce(String reason) {
        int budget = clusterProperties.getResources().getStartupScore();
        if (budget <= 0) {
            log.info("Self-pull ({}) skipped — no budget (startupScore={})", reason, budget);
            return;
        }
        int buffer = budget / 2;
        int headroom = placementService.localHeadroom();
        if (headroom <= 0) {
            log.info("Self-pull ({}) skipped — no local headroom "
                    + "(pod is at or above its maxScore)", reason);
            return;
        }
        int cap = (int) Math.min((long) budget + buffer, (long) headroom);

        int pulled = 0;
        int brought = 0;
        int skipped = 0;
        int ineligible = 0;
        // Paging, and not for size: this loop rejects candidates for a reason the
        // query cannot express (am I eligible for this project), so a page full of
        // projects meant for other pods must not read as "nothing left to do".
        // Without the skip, the earlier `if (!anyBrought) break` gave up on the
        // first such page and every eligible project behind it waited for the
        // distributor (planning/project-placement-labels.md §5).
        int skip = 0;
        while (pulled < cap) {
            List<ProjectDocument> candidates = projectService.findProjectsNeedingOwner(
                    clusterService.leaseTtl(), PAGE_SIZE, skip);
            if (candidates.isEmpty()) break;
            for (ProjectDocument p : candidates) {
                if (!placementService.isEligibleHere(p)) {
                    // Somebody else's project. Leave it for a pod whose labels
                    // match; the distributor places it.
                    ineligible++;
                    continue;
                }
                if (pulled + p.getHomeResourceScore() > cap) {
                    skipped++;
                    continue;
                }
                try {
                    lifecycleService.bring(p.getTenantId(), p.getName());
                    pulled += p.getHomeResourceScore();
                    brought++;
                } catch (ProjectManagerService.ClaimRejectedException e) {
                    // Another pod beat us to it — that is the CAS doing its job.
                    skipped++;
                } catch (RuntimeException e) {
                    log.warn("Self-pull ({}) bring failed for '{}/{}': {}",
                            reason, p.getTenantId(), p.getName(), e.toString());
                    skipped++;
                }
            }
            if (candidates.size() < PAGE_SIZE) break; // end of the candidate set
            skip += candidates.size();
        }
        if (brought > 0 || skipped > 0 || ineligible > 0) {
            log.info("Self-pull ({}): brought={} skipped={} ineligible={} score={}/{} "
                            + "(buffer={}, headroom={}, cap={})",
                    reason, brought, skipped, ineligible, pulled, budget, buffer, headroom, cap);
        } else {
            // A periodic pull finds nothing most of the time. INFO on every tick
            // would drown the log and train the reader to ignore the line that
            // matters.
            log.debug("Self-pull ({}): nothing to take", reason);
        }
    }

    /**
     * Whether a self-pull may run at all: both of its limits — am I eligible,
     * do I have room — are read from this pod's registry row, and both default
     * to <em>permissive</em> when the row is missing, because "I cannot see
     * myself" must not block a booting pod on the paths where a user is waiting.
     *
     * <p>A self-pull is not such a path. It is opportunistic, and everything it
     * skips is picked up by the distributor or by the first request — so running
     * it without limits is strictly worse than not running it. Stating the
     * precondition here is also what replaced a listener-ordering assumption
     * that Spring never guaranteed.
     */
    public boolean readyToPull() {
        clusterService.ensureRegistered();
        if (clusterService.isRegistered()) {
            return true;
        }
        log.warn("Self-pull skipped — this pod is not in the cluster registry, so its "
                + "labels and maxScore cannot be honoured");
        return false;
    }
}
