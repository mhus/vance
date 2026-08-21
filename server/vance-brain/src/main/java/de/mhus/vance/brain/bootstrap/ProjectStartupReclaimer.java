package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.project.ProjectOwnerRequirementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Boot-Self-Pull: greedily brings projects that need an owner pod and whose
 * lease nobody holds onto this pod, up to the configured
 * {@code resourcesStartupScore}.
 *
 * <p><b>No stale-claim wipe any more.</b> This used to start by nulling
 * {@code homeNode} on every project whose owning node had dropped out of the
 * live registry. With ownership expressed as a lease
 * ({@code planning/project-ownership-lease-design.md} §3) there is nothing to
 * wipe: an un-renewed lease is expired, an expired lease blocks nobody, and the
 * claim CAS takes it over on the spot. The old wipe was also the only
 * reconciliation there was, and it ran <em>only here</em> — so a pod that
 * crashed and restarted inside the stale window kept its claims forever, and a
 * long-lived cluster never reconciled at all.
 *
 * <p>Stale {@code boundConnectionId} cleanup is handled in two places
 * instead of here:
 * <ul>
 *   <li>{@link ProjectLifecycleService#bring} unbinds at the moment a
 *       project transitions from non-RUNNING to RUNNING on this pod —
 *       covers every claim path (self-pull, distributor, locator,
 *       direct-spawn) and is the latency-critical fast path for the
 *       next reconnect.</li>
 *   <li>{@code SessionStaleBindSweepTick} sweeps cluster-wide on the
 *       master pod — catches every session, including those for
 *       projects no pod currently owns ({@code _user_*}, archived).</li>
 * </ul>
 *
 * <p>Listens on {@link ApplicationReadyEvent} with low precedence so it runs
 * <em>after</em> {@code ClusterService} has registered this pod's row in
 * {@code brain_pods} — the projects we pull here are immediately reported as
 * ours in the next heartbeat.
 *
 * <p>Project status is left alone. It expresses intent ("should be live"), not
 * placement, and the lease answers placement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStartupReclaimer {

    private final ProjectService projectService;
    private final ProjectLifecycleService lifecycleService;
    private final ProjectOwnerRequirementService ownerRequirementService;
    private final ClusterService clusterService;
    private final ClusterProperties clusterProperties;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void reclaim() {
        releaseStalePins();
        selfPullProjectsNeedingOwner();
    }

    /**
     * Re-derives {@code ownerRequired} for the pinned projects before pulling
     * any of them in, so a project whose last scheduler was deleted while this
     * brain was down does not get claimed for work that no longer exists.
     *
     * <p>Runs first for that reason — the self-pull immediately below reads the
     * flag this corrects.
     */
    private void releaseStalePins() {
        try {
            int released = ownerRequirementService.releaseNoLongerQualifying();
            if (released > 0) {
                log.info("ProjectStartupReclaimer: released {} project(s) that no longer "
                        + "hold waiting work", released);
            }
        } catch (RuntimeException e) {
            // Worst case we pull in a project that has nothing to do — wasteful,
            // not wrong. Never a reason to fail the boot.
            log.warn("ProjectStartupReclaimer: owner-requirement re-derivation failed: {}",
                    e.toString());
        }
    }

    /**
     * Boot-Self-Pull (see {@code specification/cluster-project-management.md}
     * §5.1). Greedily brings projects that need an owner but hold no live lease
     * onto this pod until the configured {@code resourcesStartupScore} is
     * exhausted. EPHEMERAL and HOMELESS projects are skipped — those wait for an
     * explicit locate or live without pod-affinity.
     *
     * <p>A buffer (50% of the startup budget) lets the last candidate
     * tip slightly over the line so projects with above-average score
     * don't get stuck waiting for the distributor. The Master-Distributor
     * picks up everything we don't claim here.
     *
     * <p>The candidate set is "needs an owner and holds no live lease" — the
     * derived {@code ownerRequired} for the default {@code AUTO} projects, plus
     * anything an operator pinned to {@code PERMANENT}. Its predecessor
     * selected on {@code PERMANENT} alone and therefore matched nothing at all,
     * which is why this used to log {@code brought=0 skipped=0} on every boot
     * ({@code planning/project-ownership-lease-design.md} §1.1).
     */
    private void selfPullProjectsNeedingOwner() {
        int budget = clusterProperties.getResources().getStartupScore();
        if (budget <= 0) {
            log.info("ProjectStartupReclaimer: self-pull disabled (startupScore={})", budget);
            return;
        }
        int buffer = budget / 2;

        int pulled = 0;
        int brought = 0;
        int skipped = 0;
        // batchSize matches the distributor's appetite — small enough to
        // re-query between batches without much waste.
        final int batchSize = 20;
        while (pulled < budget) {
            List<ProjectDocument> candidates = projectService.findProjectsNeedingOwner(
                    clusterService.leaseTtl(), batchSize);
            if (candidates.isEmpty()) break;
            boolean anyBrought = false;
            for (ProjectDocument p : candidates) {
                if (pulled + p.getHomeResourceScore() > budget + buffer) {
                    skipped++;
                    continue;
                }
                try {
                    lifecycleService.bring(p.getTenantId(), p.getName());
                    pulled += p.getHomeResourceScore();
                    brought++;
                    anyBrought = true;
                } catch (ProjectManagerService.ClaimRejectedException e) {
                    // Another pod beat us to it during boot — fine.
                    skipped++;
                } catch (RuntimeException e) {
                    log.warn("ProjectStartupReclaimer: self-pull bring failed for '{}/{}': {}",
                            p.getTenantId(), p.getName(), e.toString());
                    skipped++;
                }
            }
            if (!anyBrought) break; // every candidate skipped — would loop forever
        }
        log.info("ProjectStartupReclaimer: self-pull brought={} skipped={} score={}/{} (buffer={})",
                brought, skipped, pulled, budget, buffer);
    }
}
