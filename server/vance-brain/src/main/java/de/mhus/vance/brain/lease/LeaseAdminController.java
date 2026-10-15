package de.mhus.vance.brain.lease;

import de.mhus.vance.api.insights.LeaseInsightsDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant admin's lease surface (Insights → Leases): the tenant's
 * holders and counters, and revoke of a single holder. Everything is
 * scoped to the path tenant — the global counter level and other tenants'
 * holders never reach the wire, and a revoke by token cannot cross the
 * tenant boundary ({@code specification/public/lease-system.md} §9).
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/leases")
@RequiredArgsConstructor
public class LeaseAdminController {

    private final LeaseAdminService leaseAdminService;
    private final RequestAuthority authority;

    @GetMapping
    public LeaseInsightsDto list(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        return leaseAdminService.snapshot(tenant);
    }

    /** Revokes one holder of this tenant and runs its type's handler. 404 when there is none. */
    @PostMapping("/{token}/revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable("tenant") String tenant,
            @PathVariable("token") String token,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        if (leaseAdminService.revoke(token, tenant).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No lease '" + token + "' in this tenant");
        }
        return ResponseEntity.noContent().build();
    }
}
