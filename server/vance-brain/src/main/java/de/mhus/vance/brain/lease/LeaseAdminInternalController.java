package de.mhus.vance.brain.lease;

import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin shell's lease actions that need handler dispatch — and with
 * it a brain: revoke a holder, revoke every holder of a pod, run a reaper
 * round now. Unscoped (all tenants). Authentication: shared
 * {@code X-Vance-Internal-Token} on the {@code /internal/} prefix
 * ({@code InternalAccessFilter}); a browser cannot reach these routes.
 * Read-only lease views stay in the shell itself, straight from Mongo.
 */
@RestController
@RequestMapping("/internal/lease/admin")
@RequiredArgsConstructor
public class LeaseAdminInternalController {

    private final LeaseAdminService leaseAdminService;

    /** Body of every action: the holders that were revoked. */
    public record RevokeResult(List<LeaseHolderInfo> revoked) {}

    @PostMapping("/holders/{token}/revoke")
    public ResponseEntity<RevokeResult> revoke(@PathVariable("token") String token) {
        return leaseAdminService
                .revoke(token, null)
                .map(info -> ResponseEntity.ok(new RevokeResult(List.of(info))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/pods/{podId}/revoke")
    public RevokeResult revokePod(@PathVariable("podId") String podId) {
        return new RevokeResult(leaseAdminService.revokePod(podId));
    }

    @PostMapping("/sweep")
    public ResponseEntity<Void> sweep() {
        leaseAdminService.sweep();
        return ResponseEntity.noContent().build();
    }
}
