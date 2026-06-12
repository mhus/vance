package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reclaims pod-level state on startup.
 *
 * <p>Wipe stale {@code homeNode} values on every project whose owning
 * cluster node is no longer in the live registry. Every fresh pod boot
 * picks a new node name (see {@code ClusterNodeNameGenerator}), so the
 * previous incarnation's claims will never be inherited — they would
 * otherwise block the current pod from claiming via the CAS predicate.
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
 * <p>Listens on {@link ApplicationReadyEvent} with low precedence so it
 * runs <em>after</em> {@code ClusterService} has registered this pod's
 * row in {@code brain_pods} — otherwise we'd see our own node-name as
 * "not live" and wipe a claim we just took.
 *
 * <p>Project status is left alone. The first session-bind / wakeup-tick
 * after startup refreshes {@code homeNode} + {@code claimedAt}
 * through {@link ProjectManagerService#claimForLocalPod} via the
 * regular lifecycle path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStartupReclaimer {

    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final ProjectLifecycleService lifecycleService;
    private final ClusterService clusterService;
    private final ClusterProperties clusterProperties;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void reclaim() {
        clearStaleClusterClaims();
        selfPullPermanentProjects();
    }

    /**
     * Idempotent bulk-cleanup of project claims whose owning cluster
     * node has dropped out of the live registry. Two pods racing here
     * converge on the same final state: the {@code updateMulti} filter
     * matches the same set of stale documents (whatever pods are live
     * is observed from the same Mongo), and the update body is
     * identical. {@code homeNode=null} is the unowned state.
     */
    private void clearStaleClusterClaims() {
        Set<String> liveClusters = new HashSet<>(clusterService.liveClusterNodeNames());
        // Belt-and-suspenders: always include this pod's node name even
        // if the brain_pods registration is still in flight.
        String selfCluster = clusterService.selfNodeName();
        if (selfCluster != null && !selfCluster.isBlank()) {
            liveClusters.add(selfCluster);
        }
        long cleared = projectService.clearStaleHomeNodes(liveClusters);
        if (cleared > 0) {
            log.info("ProjectStartupReclaimer: cleared {} stale home-cluster claim(s); live={}",
                    cleared, liveClusters);
        } else {
            log.info("ProjectStartupReclaimer: no stale home-cluster claims; live={}",
                    liveClusters);
        }
    }

    /**
     * Boot-Self-Pull (see {@code specification/cluster-project-management.md}
     * §5.1). Greedily brings PERMANENT-orphans onto this pod until the
     * configured {@code resourcesStartupScore} is exhausted. EPHEMERAL
     * and HOMELESS projects are skipped — those wait for an explicit
     * locate or live without pod-affinity.
     *
     * <p>A buffer (50% of the startup budget) lets the last candidate
     * tip slightly over the line so projects with above-average score
     * don't get stuck waiting for the distributor. The Master-Distributor
     * picks up everything we don't claim here.
     */
    private void selfPullPermanentProjects() {
        int budget = clusterProperties.getResources().getStartupScore();
        if (budget <= 0) {
            log.info("ProjectStartupReclaimer: self-pull disabled (startupScore={})", budget);
            return;
        }
        int buffer = budget / 2;
        Set<String> liveClusters = new HashSet<>(clusterService.liveClusterNodeNames());
        String selfNode = clusterService.selfNodeName();
        if (selfNode != null && !selfNode.isBlank()) liveClusters.add(selfNode);

        int pulled = 0;
        int brought = 0;
        int skipped = 0;
        // batchSize matches the distributor's appetite — small enough to
        // re-query liveClusters / homeNode between batches without much waste.
        final int batchSize = 20;
        while (pulled < budget) {
            List<ProjectDocument> candidates =
                    projectService.findPermanentOrphans(liveClusters, batchSize);
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
