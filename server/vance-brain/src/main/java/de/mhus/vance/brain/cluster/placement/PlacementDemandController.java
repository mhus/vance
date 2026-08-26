package de.mhus.vance.brain.cluster.placement;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read direction of the placement demand.
 *
 * <p><b>Not a diagnostic endpoint.</b> It serves the same document the webhook
 * pushes, and it exists for a reason the push cannot cover: a control loop that
 * also decides when to take pods <em>away</em> has to read the current state,
 * and no event tells it that — not a delivered one and not a missed one
 * ({@code planning/project-placement-labels.md} §6.3).
 *
 * <p>Own path rather than {@code /internal/cluster/master/…}: this is a read
 * that does not need the master role — the pending set is a cluster-wide query
 * and {@code pendingSince} sits on the document. Under the master prefix it
 * would either have to carry that controller's {@code 421 Misdirected Request}
 * redirect or be explained as an exception to it.
 *
 * <p>Auth is the shared-token {@code /internal/} surface
 * ({@code InternalAccessFilter}), same as the other pod-to-pod endpoints.
 */
@RestController
@RequestMapping("/internal/cluster/placement")
@RequiredArgsConstructor
public class PlacementDemandController {

    private final PlacementDemandService demandService;

    @GetMapping("/demand")
    public ResponseEntity<PlacementDemand> demand(
            @RequestParam(value = "tenant", required = false) @Nullable String tenant) {
        return ResponseEntity.ok(demandService.currentDemand(tenant));
    }
}
