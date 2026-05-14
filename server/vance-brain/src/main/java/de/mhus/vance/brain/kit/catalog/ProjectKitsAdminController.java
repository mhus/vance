package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.api.kit.ProjectKitsScanRequestDto;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST endpoints for the tenant-wide project-kits catalog. The
 * three operations are split intentionally so anus can compose
 * merge/overwrite/dry-run logic client-side:
 *
 * <ul>
 *   <li>{@code GET    /catalog} — return the current tenant catalog.</li>
 *   <li>{@code PUT    /catalog} — replace the catalog with the body.</li>
 *   <li>{@code POST   /scan}   — clone a kits-repo and return a DTO
 *       built from its {@code kits/} subdirectories, without
 *       persisting.</li>
 * </ul>
 *
 * <p>Auth: tenant-level {@link Action#ADMIN} on every call.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md}.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/project-kits")
@RequiredArgsConstructor
@Slf4j
public class ProjectKitsAdminController {

    private final ProjectKitsCatalogService catalogService;
    private final KitCatalogScanService scanService;
    private final RequestAuthority authority;

    @GetMapping("/catalog")
    public ProjectKitsCatalogDto load(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        return catalogService.load(tenant);
    }

    @PutMapping("/catalog")
    public ProjectKitsCatalogDto save(
            @PathVariable("tenant") String tenant,
            @RequestBody ProjectKitsCatalogDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            catalogService.save(tenant, body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return catalogService.load(tenant);
    }

    @PostMapping("/scan")
    public ProjectKitsCatalogDto scan(
            @PathVariable("tenant") String tenant,
            @RequestBody ProjectKitsScanRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            return scanService.scan(body.getGitUrl(), body.getRef(), body.getToken());
        } catch (KitException e) {
            log.warn("kit catalog scan failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
