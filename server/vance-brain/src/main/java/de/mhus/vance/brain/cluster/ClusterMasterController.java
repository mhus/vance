package de.mhus.vance.brain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.mhus.vance.brain.cluster.placement.ClusterFullException;
import de.mhus.vance.brain.cluster.placement.PlacementDecision;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master-only endpoints. Lives under {@code /internal/cluster/master/}
 * and answers only when the local pod currently holds the Cluster-Master
 * lease — see {@code specification/cluster-project-management.md} §7.1.
 *
 * <p>If the lease has rotated elsewhere, callers get HTTP 421
 * (Misdirected Request) with the current master's endpoint in the
 * body so they can retry against the right pod.
 */
@RestController
@RequestMapping("/internal/cluster/master")
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ClusterMasterController {

    private final ClusterMasterService masterService;
    private final ClusterService clusterService;
    private final ProjectService projectService;
    private final ProjectPlacementService placementService;

    /**
     * Pick a target pod and dispatch {@code bring} to it. Returns the
     * chosen pod's node-name + endpoint so the caller knows where the
     * project now lives.
     *
     * <p><b>No in-tree caller since the placement facade landed.</b> Every pod
     * can now compute the same decision from the same pod list, so asking the
     * master over HTTP buys nothing — and the fallback chain around that call
     * ("no master endpoint reachable → bring locally anyway") was overriding
     * the capacity decision it had just made. The endpoint stays because it is
     * a wire surface: during a rolling upgrade an older pod still calls it.
     */
    @PostMapping("/spawn")
    public ResponseEntity<?> spawn(@RequestBody HttpClusterBringClient.SpawnRequest req) {
        if (req == null || req.tenantId() == null || req.projectName() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!masterService.isLocalPodMaster()) {
            return notMasterRedirect();
        }
        ProjectDocument project;
        try {
            project = projectService.findByTenantAndName(req.tenantId(), req.projectName())
                    .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                            "Project '" + req.projectName() + "' not found in tenant '"
                                    + req.tenantId() + "'"));
        } catch (ProjectService.ProjectNotFoundException e) {
            return ResponseEntity.status(404).body(new MessageResponse(e.getMessage()));
        }
        try {
            PlacementDecision decision =
                    placementService.place(project, PlacementTrigger.REMOTE_REQUEST);
            return ResponseEntity.ok(switch (decision) {
                case PlacementDecision.On on -> new HttpClusterBringClient.SpawnResponse(
                        on.pod().getNodeName(), on.pod().getEndpoint());
                // Podless / HOMELESS: there is no pod row to name, and the
                // project now runs here. Answer with our own identity rather
                // than inventing a document.
                default -> new HttpClusterBringClient.SpawnResponse(
                        clusterService.selfNodeName(), clusterService.selfEndpoint());
            });
        } catch (ClusterFullException e) {
            log.warn("Master spawn rejected ({}) for '{}/{}': {}",
                    e.getGap(), req.tenantId(), req.projectName(), e.getMessage());
            return ResponseEntity.status(503).body(new MessageResponse(e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Optional<ClusterMasterDocument> leaseOpt = masterService.currentLease();
        if (leaseOpt.isEmpty()) {
            return ResponseEntity.ok(new StatusResponse(null, null, null, null, false));
        }
        ClusterMasterDocument lease = leaseOpt.get();
        return ResponseEntity.ok(new StatusResponse(
                lease.getCurrentPodId(),
                lease.getCurrentNodeName(),
                lease.getCurrentEndpoint(),
                lease.getLeaseUntil(),
                masterService.isLocalPodMaster()));
    }

    private ResponseEntity<?> notMasterRedirect() {
        String currentMaster = masterService.currentLease()
                .map(ClusterMasterDocument::getCurrentEndpoint)
                .orElse("");
        return ResponseEntity.status(421).body(new MessageResponse(
                currentMaster.isBlank()
                        ? "No master available — retry shortly"
                        : "Not master, current master at " + currentMaster));
    }

    public record StatusResponse(
            @JsonProperty("currentPodId") @Nullable String currentPodId,
            @JsonProperty("currentNodeName") @Nullable String currentNodeName,
            @JsonProperty("currentEndpoint") @Nullable String currentEndpoint,
            @JsonProperty("leaseUntil") @Nullable Instant leaseUntil,
            @JsonProperty("localIsMaster") boolean localIsMaster) {}

    public record MessageResponse(@JsonProperty("message") String message) {}
}
