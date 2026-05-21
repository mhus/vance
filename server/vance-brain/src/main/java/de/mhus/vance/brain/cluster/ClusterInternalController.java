package de.mhus.vance.brain.cluster;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.project.ProjectDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
