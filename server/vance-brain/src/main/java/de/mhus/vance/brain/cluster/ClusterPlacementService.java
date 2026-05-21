package de.mhus.vance.brain.cluster;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "Pick a pod, dispatch the bring" — the placement primitive shared by
 * the Cluster-Master spawn endpoint and the {@link ClusterDistributorTick}.
 * Master-role gating is the caller's job; this service does pure
 * "place this project somewhere it fits".
 *
 * <p>Greedy by load fraction ({@code currentScore/maxScore}). Returns
 * the chosen target's {@link BrainPodDocument} on success, throws
 * {@link ClusterMasterService.ClusterFullException} when nobody has
 * room.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterPlacementService {

    private final ClusterService clusterService;
    private final ProjectService projectService;
    private final ProjectLifecycleService lifecycleService;
    private final ClusterBringClient bringClient;

    /**
     * Place {@code (tenantId, projectName)} on whichever live pod has
     * the lowest load fraction and still fits its
     * {@code homeResourceScore}. Dispatches either a local
     * {@link ProjectLifecycleService#bring} or a remote
     * {@link ClusterBringClient#requestBring}. Returns the target pod.
     */
    public BrainPodDocument placeProject(String tenantId, String projectName) {
        ProjectDocument project = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '"
                                + tenantId + "'"));
        return placeProject(project);
    }

    public BrainPodDocument placeProject(ProjectDocument project) {
        List<BrainPodDocument> pods = clusterService.liveClusterPods();
        if (pods.isEmpty()) {
            throw new ClusterMasterService.ClusterFullException(
                    "No live pods in this cluster — cannot place project '"
                            + project.getTenantId() + "/" + project.getName() + "'");
        }
        BrainPodDocument target = pickTarget(pods, project.getHomeResourceScore());
        if (target == null) {
            throw new ClusterMasterService.ClusterFullException(
                    "All pods at capacity — cannot place project '"
                            + project.getTenantId() + "/" + project.getName()
                            + "' (score=" + project.getHomeResourceScore() + ")");
        }
        dispatchBring(target, project);
        return target;
    }

    /**
     * Dispatch a bring to {@code target} — local if it's this pod,
     * otherwise a {@link ClusterBringClient#requestBring} hop.
     */
    public void dispatchBring(BrainPodDocument target, ProjectDocument project) {
        String selfNode = clusterService.selfNodeName();
        boolean isLocal = selfNode != null && selfNode.equals(target.getNodeName());
        if (isLocal) {
            lifecycleService.bring(project.getTenantId(), project.getName());
            log.info("ClusterPlacementService: placed '{}/{}' locally on '{}'",
                    project.getTenantId(), project.getName(), selfNode);
        } else {
            bringClient.requestBring(target.getEndpoint(),
                    project.getTenantId(), project.getName());
            log.info("ClusterPlacementService: placed '{}/{}' on remote pod '{}' ({})",
                    project.getTenantId(), project.getName(),
                    target.getNodeName(), target.getEndpoint());
        }
    }

    /**
     * Returns the lightest pod with room for {@code score}, or
     * {@code null} if no pod has capacity. {@code pods} is expected
     * pre-sorted by load ascending (as returned by
     * {@link ClusterService#liveClusterPods}).
     */
    @org.jspecify.annotations.Nullable
    public BrainPodDocument pickTarget(List<BrainPodDocument> pods, int score) {
        for (BrainPodDocument p : pods) {
            int max = Math.max(1, p.getResourcesMaxScore());
            if (p.getResourcesCurrentScore() + score <= max) {
                return p;
            }
        }
        return null;
    }
}
