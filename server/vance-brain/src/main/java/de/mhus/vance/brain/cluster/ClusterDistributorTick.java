package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic re-placement of PERMANENT-orphan projects on healthy pods.
 * Runs on every pod but no-ops unless the local pod currently holds the
 * Cluster-Master lease — see
 * {@code specification/cluster-project-management.md} §5.2.
 *
 * <p>Per tick: read orphans + live pods, then ask
 * {@link ClusterPlacementService} to pick + dispatch each one. The
 * service uses the same greedy strategy as the direct-spawn endpoint
 * so both spawn paths agree on "who has room".
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
    private final ClusterPlacementService placementService;

    @Scheduled(fixedDelayString = "${vance.cluster.master.distributorInterval:PT60S}",
            initialDelayString = "${vance.cluster.master.distributorInitialDelay:PT45S}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            distribute();
        } catch (RuntimeException e) {
            log.warn("ClusterDistributorTick: round failed: {}", e.toString());
        }
    }

    void distribute() {
        Set<String> liveClusters = new HashSet<>(clusterService.liveClusterNodeNames());
        int maxPerTick = Math.max(1, properties.getMaster().getMaxPerTick());
        List<ProjectDocument> orphans = projectService.findPermanentOrphans(liveClusters, maxPerTick);
        if (orphans.isEmpty()) {
            return;
        }

        // We pick targets ourselves (rather than calling placementService.placeProject
        // per orphan) so we can carry a projected-scores buffer across orphans within
        // the same round — otherwise we'd over-book the cheapest pod every tick.
        List<BrainPodDocument> pods = clusterService.liveClusterPods();
        if (pods.isEmpty()) {
            log.warn("ClusterDistributorTick: no live pods to place {} orphan(s) on", orphans.size());
            return;
        }
        int[] projectedScores = pods.stream()
                .mapToInt(BrainPodDocument::getResourcesCurrentScore)
                .toArray();

        int placed = 0;
        int rejected = 0;
        for (ProjectDocument p : orphans) {
            int idx = pickIndex(pods, projectedScores, p.getHomeResourceScore());
            if (idx < 0) {
                log.warn("CLUSTER-FULL: project '{}/{}' (score={}) cannot be placed — all pods at capacity",
                        p.getTenantId(), p.getName(), p.getHomeResourceScore());
                rejected++;
                continue;
            }
            BrainPodDocument target = pods.get(idx);
            try {
                placementService.dispatchBring(target, p);
                projectedScores[idx] += p.getHomeResourceScore();
                placed++;
            } catch (RuntimeException e) {
                log.warn("ClusterDistributorTick: bring failed for '{}/{}' on '{}': {}",
                        p.getTenantId(), p.getName(), target.getNodeName(), e.toString());
                rejected++;
            }
        }
        log.info("ClusterDistributorTick: orphans={} placed={} rejected={}",
                orphans.size(), placed, rejected);
    }

    private int pickIndex(List<BrainPodDocument> pods, int[] projectedScores, int score) {
        for (int i = 0; i < pods.size(); i++) {
            int max = pods.get(i).getResourcesMaxScore();
            if (projectedScores[i] + score <= max) {
                return i;
            }
        }
        return -1;
    }
}
