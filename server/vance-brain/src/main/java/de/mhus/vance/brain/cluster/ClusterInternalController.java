package de.mhus.vance.brain.cluster;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodSelector;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pod-to-pod control endpoints. All paths sit under {@code /internal/}
 * and rely on {@code InternalAccessFilter} for authentication via the
 * shared {@code X-Vance-Internal-Token} header — see
 * {@code specification/cluster-project-management.md} §7. No JWT
 * tenant-scoping applies here.
 */
@RestController
@RequestMapping("/internal/cluster")
@RequiredArgsConstructor
@Slf4j
public class ClusterInternalController {

    private final ProjectLifecycleService lifecycleService;
    private final BrainPodService brainPodService;

    /**
     * Set a pod's placement attributes from outside the process — the write
     * side of {@code planning/project-placement-labels.md} §4.
     *
     * <p>Served by <b>any</b> pod, not just the one being labelled: this is a
     * row write, and requiring the call to reach the target would make labelling
     * a pod impossible exactly when it matters (a pod that is unreachable, or
     * one being prepared before anything is routed to it).
     *
     * <p>{@code labels} replaces the whole map; {@code null} on either field
     * means "leave unchanged". A malformed key is a {@code 400}, an unknown
     * {@code podId} a {@code 404}.
     */
    @PatchMapping("/pods/{podId}/placement")
    public ResponseEntity<?> updatePodPlacement(
            @PathVariable("podId") String podId,
            @RequestBody PodPlacementRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return brainPodService.updatePlacement(podId, req.labels(), req.exclusive())
                    .<ResponseEntity<?>>map(pod -> {
                        log.info("Pod '{}' placement updated: labels={} exclusive={}",
                                pod.getNodeName(), pod.getLabels(), pod.isExclusive());
                        return ResponseEntity.ok(
                                new PodPlacementResponse(pod.getNodeName(),
                                        pod.getLabels(), pod.isExclusive()));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).build());
        } catch (PodSelector.InvalidLabelException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** {@code null} on a field means "leave unchanged". */
    public record PodPlacementRequest(
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive) {}

    public record PodPlacementResponse(
            String nodeName,
            @Nullable Map<String, String> labels,
            boolean exclusive) {}

    /**
     * Dispatched by another pod (master distributor or direct-spawn
     * source). Runs {@code bring} locally and returns the resulting
     * {@code homeNode}. Score-/capacity-validation is the caller's job
     * — see the spec §5.
     */
    @PostMapping("/bring")
    public ResponseEntity<HttpClusterBringClient.BringResponse> bring(
            @RequestBody HttpClusterBringClient.BringRequest req) {
        if (req == null || req.tenantId() == null || req.projectName() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ProjectDocument doc = lifecycleService.bring(req.tenantId(), req.projectName());
            String homeNode = doc.getHomeNode() == null ? "" : doc.getHomeNode();
            return ResponseEntity.ok(new HttpClusterBringClient.BringResponse(homeNode));
        } catch (ProjectManagerService.ClaimRejectedException e) {
            log.info("Cluster bring rejected for '{}/{}': {}",
                    req.tenantId(), req.projectName(), e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }
}
