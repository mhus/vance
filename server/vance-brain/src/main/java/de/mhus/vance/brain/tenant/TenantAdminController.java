package de.mhus.vance.brain.tenant;

import de.mhus.vance.api.tenant.TenantDto;
import de.mhus.vance.api.tenant.TenantUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read + update of the caller's own tenant. {@code name} is immutable —
 * everything else (title, enabled flag) is editable here.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before requests reach this controller, so the
 * {@code @PathVariable} {@code tenant} is always the authenticated tenant.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/tenant")
@RequiredArgsConstructor
@Slf4j
public class TenantAdminController {

    private final TenantService tenantService;
    private final RequestAuthority authority;

    @GetMapping
    public TenantDto get(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        TenantDocument doc = tenantService.findByName(tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Tenant '" + tenant + "' not found"));
        return toDto(doc);
    }

    @PutMapping
    public TenantDto update(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody TenantUpdateRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            TenantDocument saved = tenantService.update(tenant, request.getTitle(), request.getEnabled());
            return toDto(saved);
        } catch (TenantService.TenantNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static TenantDto toDto(TenantDocument doc) {
        return TenantDto.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .enabled(doc.isEnabled())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
