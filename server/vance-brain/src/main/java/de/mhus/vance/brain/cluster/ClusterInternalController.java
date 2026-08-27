package de.mhus.vance.brain.cluster;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.cluster.BrainPodCapacity;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodSelector;
import de.mhus.vance.brain.cluster.placement.ClusterFullException;
import de.mhus.vance.brain.cluster.placement.PlacementDecision;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ProjectService projectService;
    private final ClusterService clusterService;
    private final ProjectPlacementService placementService;

    /**
     * Where a project currently lives, or {@code 404} when nobody holds a live
     * lease on it.
     *
     * <p>Exists so a caller does not have to learn the lease semantics to find
     * the home pod. {@code homePodId} alone is not the answer — a lease that
     * stopped being renewed names a holder that is gone — and the TTL that
     * decides it is brain configuration. Teaching an external client that rule
     * would mean duplicating a timeout it cannot see; asking is one call.
     *
     * <p>The drain in {@link #release} needs exactly this: that call has to
     * reach the holding pod, and this is how a caller finds it.
     */
    @GetMapping("/projects/home")
    public ResponseEntity<?> projectHome(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("projectName") String projectName) {
        Optional<ProjectDocument> project =
                projectService.findByTenantAndName(tenantId, projectName);
        if (project.isEmpty()) {
            return ResponseEntity.status(404).body("no such project");
        }
        if (ProjectService.isPodless(projectName)) {
            return ResponseEntity.status(404).body(
                    "podless project — lives wherever the client's WS landed, holds no lease");
        }
        Optional<String> podId = ProjectOwnership.liveOwnerPodId(
                project.get(), Instant.now(), clusterService.leaseTtl());
        if (podId.isEmpty()) {
            return ResponseEntity.status(404).body("no live lease — nobody owns it");
        }
        return clusterService.resolveEndpointByPodId(podId.get())
                .<ResponseEntity<?>>map(endpoint -> ResponseEntity.ok(new ProjectHomeResponse(
                        podId.get(), project.get().getHomeNode(), endpoint)))
                // Lease valid but the pod is not answering: the registry row is
                // gone, stopped or stale. Distinct from "nobody owns it",
                // because the two need different actions — wait versus place.
                .orElseGet(() -> ResponseEntity.status(409).body(
                        "leased by '" + project.get().getHomeNode()
                                + "' but that pod has no live endpoint"));
    }

    public record ProjectHomeResponse(
            String podId, @Nullable String nodeName, String endpoint) {}

    /**
     * Run the full placement for an existing project: pick a pod the way every
     * other path does, then dispatch the bring there.
     *
     * <p><b>Not a local claim.</b> {@code POST /admin/projects/{name}/resume}
     * brings the project up on whichever pod happened to answer the request,
     * which is right for an operator who means "start it here" and wrong for
     * "start it wherever it belongs". This one asks
     * {@code ProjectPlacementService} with {@link PlacementTrigger#ADMIN}, so
     * the local pod gets no preference and the labels decide.
     *
     * <p>Four outcomes, deliberately distinguished — the point of the endpoint
     * is that a caller learns <em>why</em> nothing happened:
     * <ul>
     *   <li>{@code 200} — placed, with the pod that took it.</li>
     *   <li>{@code 409} — already owned by a live pod. Reported rather than
     *       attempted: the claim CAS would refuse anyway, and "it already runs
     *       over there" is a different message from "it could not be placed".</li>
     *   <li>{@code 503} — unschedulable, with the {@code PlacementGap}. That is
     *       the answer that says whether to provide a different kind of pod or
     *       more of the same.</li>
     *   <li>{@code 502} — a pod was chosen and the bring to it failed. The
     *       decision was sound and the execution was not.</li>
     * </ul>
     */
    @PostMapping("/place")
    public ResponseEntity<?> place(@RequestBody HttpClusterBringClient.BringRequest req) {
        if (req == null || req.tenantId() == null || req.projectName() == null) {
            return ResponseEntity.badRequest().build();
        }
        Optional<ProjectDocument> found =
                projectService.findByTenantAndName(req.tenantId(), req.projectName());
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body("no such project");
        }
        ProjectDocument project = found.get();
        Optional<String> owner = ProjectOwnership.liveOwnerPodId(
                project, Instant.now(), clusterService.leaseTtl());
        if (owner.isPresent()) {
            return ResponseEntity.status(409).body(new PlaceResponse(
                    false, project.getHomeNode(),
                    clusterService.resolveEndpointByPodId(owner.get()).orElse(null),
                    null, "already owned by a live pod"));
        }
        try {
            PlacementDecision decision =
                    placementService.place(project, PlacementTrigger.ADMIN);
            String nodeName = decision instanceof PlacementDecision.On on
                    ? on.pod().getNodeName() : clusterService.selfNodeName();
            String endpoint = decision instanceof PlacementDecision.On on
                    ? on.pod().getEndpoint() : clusterService.selfEndpoint();
            return ResponseEntity.ok(new PlaceResponse(
                    true, nodeName, endpoint, null, "placed"));
        } catch (ClusterFullException e) {
            log.info("Placement refused for '{}/{}': {}",
                    req.tenantId(), req.projectName(), e.getGap());
            return ResponseEntity.status(503).body(new PlaceResponse(
                    false, null, null, e.getGap().name(), e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Placement dispatch failed for '{}/{}': {}",
                    req.tenantId(), req.projectName(), e.toString());
            return ResponseEntity.status(502).body(new PlaceResponse(
                    false, null, null, null, "dispatch failed: " + e.getMessage()));
        }
    }

    /**
     * {@code gap} is set only on {@code 503} — it answers "what kind of pod is
     * missing", which a transport failure cannot.
     */
    public record PlaceResponse(
            boolean placed,
            @Nullable String nodeName,
            @Nullable String endpoint,
            @Nullable String gap,
            String message) {}

    /**
     * Hand a project over: stop it here, snapshot its workspace, drop the
     * lease. The inverse of {@link #bring} and the second half of a drain.
     *
     * <p><b>Two steps, and the order is the contract.</b> After a release this
     * pod is still eligible and often the least loaded, so it wins the project
     * back on the next tick. A drain is therefore: make the pod ineligible
     * (labels / {@code exclusive} via {@code PATCH …/pods/{podId}/placement}),
     * <em>then</em> release. Reversed, it bounces back. That cannot be enforced
     * here without a "do not take this one for a while" state — a fourth
     * lifetime next to intent, ownership and activation — so it is documented
     * instead ({@code planning/project-placement-labels.md} §8).
     *
     * <p>Must reach the holding pod: unlike the placement write below, this one
     * tears down in-memory state that only exists in that process. {@code 409}
     * when this pod does not hold the lease.
     */
    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody HttpClusterBringClient.BringRequest req) {
        if (req == null || req.tenantId() == null || req.projectName() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            boolean released = lifecycleService.release(req.tenantId(), req.projectName());
            if (!released) {
                log.info("Cluster release refused for '{}/{}': not held by this pod",
                        req.tenantId(), req.projectName());
                return ResponseEntity.status(409).build();
            }
            return ResponseEntity.ok().build();
        } catch (ProjectService.SystemProjectProtectedException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

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
            return brainPodService.updatePlacement(
                            podId, req.labels(), req.exclusive(),
                            req.maxScoreOverride(),
                            Boolean.TRUE.equals(req.clearMaxScoreOverride()))
                    .<ResponseEntity<?>>map(pod -> {
                        log.info("Pod '{}' placement updated: labels={} exclusive={} "
                                        + "maxScore={} override={}",
                                pod.getNodeName(), pod.getLabels(), pod.isExclusive(),
                                pod.getResourcesMaxScore(), pod.getResourcesMaxScoreOverride());
                        return ResponseEntity.ok(PodPlacementResponse.of(pod));
                    })
                    .orElseGet(() -> ResponseEntity.status(404).build());
        } // InvalidLabelException IS an IllegalArgumentException — one catch covers
        // both the label grammar and the score range.
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * {@code null} on a field means "leave unchanged".
     *
     * <p>{@code clearMaxScoreOverride} exists because {@code null} is already
     * taken: "drop the override, go back to the configured cap" has to be
     * expressible, and it is not the same statement as "I am not talking about
     * the override".
     */
    public record PodPlacementRequest(
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive,
            @Nullable Integer maxScoreOverride,
            @Nullable Boolean clearMaxScoreOverride) {}

    /**
     * Carries both capacity layers plus the effective value, so a caller does
     * not have to re-implement the precedence rule to display what it just set.
     */
    public record PodPlacementResponse(
            String nodeName,
            @Nullable Map<String, String> labels,
            boolean exclusive,
            int maxScore,
            @Nullable Integer maxScoreOverride,
            int effectiveMaxScore) {

        static PodPlacementResponse of(BrainPodDocument pod) {
            return new PodPlacementResponse(
                    pod.getNodeName(), pod.getLabels(), pod.isExclusive(),
                    pod.getResourcesMaxScore(), pod.getResourcesMaxScoreOverride(),
                    BrainPodCapacity.effectiveMaxScore(pod));
        }
    }

    /**
     * Set what a project requires of a pod, from outside the tenant's own admin
     * surface — the symmetric counterpart to the pod write above.
     *
     * <p><b>Why this exists next to
     * {@code POST /brain/{tenant}/admin/projects/{name}/placement}, which does
     * the same thing.</b> They serve two different actors and the difference is
     * which token they carry. The admin route belongs to a tenant administrator
     * and is reachable from the Web-UI with a user token. This one belongs to
     * the infrastructure actor that also labels the pods — and it must not need
     * a per-tenant admin identity to do so, because the placement of a project
     * is a cluster-level concern and that actor holds one credential for the
     * whole cluster, not one per tenant.
     *
     * <p>The price, stated: {@code /internal/**} has <b>no tenant
     * authorization</b>. Whoever holds the shared secret can set a selector in
     * any tenant — exactly as they can already {@code bring} any project. Here
     * "technical" means uniformly trusted, not narrowly scoped.
     *
     * <p>Served by any pod: a document write, like the pod placement above and
     * unlike {@code release}.
     */
    @PostMapping("/projects/placement")
    public ResponseEntity<?> updateProjectPlacement(@RequestBody ProjectPlacementRequest req) {
        if (req == null || req.tenantId() == null || req.projectName() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ProjectDocument project = projectService.setPlacement(
                    req.tenantId(), req.projectName(),
                    req.placementSelector(), req.homeResourceScore());
            log.info("Project '{}/{}' placement updated: selector={} score={}",
                    req.tenantId(), req.projectName(),
                    project.getPlacementSelector(), project.getHomeResourceScore());
            return ResponseEntity.ok(new ProjectPlacementResponse(
                    project.getTenantId(), project.getName(),
                    project.getPlacementSelector(), project.getHomeResourceScore(),
                    project.getPendingSince()));
        } catch (ProjectService.ProjectNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } // InvalidLabelException IS an IllegalArgumentException — one catch covers
        // both the label grammar and the score range.
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** {@code null} on selector or score means "leave unchanged". */
    public record ProjectPlacementRequest(
            String tenantId,
            String projectName,
            @Nullable Map<String, String> placementSelector,
            @Nullable Integer homeResourceScore) {}

    /**
     * Echoes {@code pendingSince} so a caller that just widened a selector can
     * see whether the project is still waiting — without a second round trip to
     * the demand endpoint.
     */
    public record ProjectPlacementResponse(
            String tenantId,
            String projectName,
            @Nullable Map<String, String> placementSelector,
            int homeResourceScore,
            @Nullable Instant pendingSince) {}

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
