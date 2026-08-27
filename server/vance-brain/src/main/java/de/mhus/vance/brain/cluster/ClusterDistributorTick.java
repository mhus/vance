package de.mhus.vance.brain.cluster;

import de.mhus.vance.brain.cluster.placement.PlacementDecision;
import de.mhus.vance.brain.cluster.placement.PlacementDemandNotifier;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic re-placement of owner-needing projects with no live lease onto
 * healthy pods.
 * Runs on every pod but no-ops unless the local pod currently holds the
 * Cluster-Master lease — see
 * {@code specification/cluster-project-management.md} §5.2.
 *
 * <p>Per tick: read the orphans, hand the whole list to
 * {@link ProjectPlacementService#decideBatch} — one decision per orphan with a
 * reservation buffer carried across the round — and dispatch them one by one so
 * a single failure costs one project, not the round.
 *
 * <p>Race-freeness against parallel ticks comes from the CAS in
 * {@code ProjectService.claim}: even if two pods pick the same orphan,
 * only one bring succeeds, the other is rejected.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ClusterDistributorTick {

    private final ClusterMasterService masterService;
    private final ClusterService clusterService;
    private final ClusterProperties properties;
    private final ProjectService projectService;
    private final ProjectPlacementService placementService;
    private final PlacementDemandNotifier demandNotifier;
    private final MegadodoService megadodoService;

    @Scheduled(fixedDelayString = "${vance.cluster.master.distributor-interval:PT60S}",
            initialDelayString = "${vance.cluster.master.distributor-initial-delay:PT45S}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            distribute();
        } catch (RuntimeException e) {
            log.warn("ClusterDistributorTick: round failed: {}", e.toString());
        }
        // After distributing, not before: what could be placed has been placed,
        // so what remains is genuine demand. Reporting first would announce a
        // need this very round was about to satisfy.
        demandNotifier.notifyRound();
    }

    void distribute() {
        int maxPerTick = Math.max(1, properties.getMaster().getMaxPerTick());
        List<ProjectDocument> orphans =
                projectService.findProjectsNeedingOwner(clusterService.leaseTtl(), maxPerTick);
        if (orphans.isEmpty()) {
            return;
        }

        // One batch decision for the whole round: the reservation buffer inside
        // decideBatch is what stops every orphan from landing on the cheapest
        // pod. It used to live here as a private copy of the pick loop, which is
        // how the two copies drifted apart (planning/project-placement-labels.md §1.3).
        List<PlacementDecision> decisions = placementService.decideBatch(orphans);

        int placed = 0;
        int rejected = 0;
        for (int i = 0; i < orphans.size(); i++) {
            ProjectDocument p = orphans.get(i);
            PlacementDecision decision = decisions.get(i);
            if (decision instanceof PlacementDecision.Unschedulable unschedulable) {
                log.warn("UNSCHEDULABLE: project '{}/{}' (score={}) — {}",
                        p.getTenantId(), p.getName(), p.getHomeResourceScore(),
                        unschedulable.gap());
                homeless(p, unschedulable.gap().name());
                rejected++;
                continue;
            }
            try {
                placementService.dispatch(decision, p);
                placed++;
            } catch (RuntimeException e) {
                // The decision was sound and the execution was not — an incident,
                // not unmet demand. A new pod would not fix it, so it stays in the
                // journal and never becomes a PlacementGap (see PlacementGap).
                log.warn("ClusterDistributorTick: bring failed for '{}/{}': {}",
                        p.getTenantId(), p.getName(), e.toString());
                homeless(p, "dispatch failed: " + e);
                rejected++;
            }
        }
        log.info("ClusterDistributorTick: orphans={} placed={} rejected={}",
                orphans.size(), placed, rejected);
    }

    /**
     * Record that a project wants to run and has nowhere to do it.
     *
     * <p>Only for the ones that could <em>not</em> be placed. A successfully
     * placed orphan needs no row here — the claim on the target pod writes
     * one, and it says where the project came from too.
     *
     * <p>Repeats every round for as long as it lasts, unlike the transition
     * rows around it. That is deliberate: this is not a state change that
     * happened once, it is an ongoing incident, and every round is another
     * round in which a project that wants to run did not.
     */
    private void homeless(ProjectDocument p, String reason) {
        megadodoService.projectHomeless(
                p.getTenantId(), p.getName(), p.getHomeNode(), p.getClaimedAt(), reason);
    }

}
