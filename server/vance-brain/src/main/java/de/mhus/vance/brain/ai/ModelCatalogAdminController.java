package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.discovery.ModelDiscoveryService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand cache refresh for {@link ModelCatalog}. The scheduled
 * refresh runs every 30 minutes; this endpoint exists so operators
 * can force an immediate reload after editing
 * {@code _vance/model/...} documents — typically wired to a button
 * in the Workspace / Profile setting form.
 *
 * <p>Refresh is global to the brain pod, but the route is
 * tenant-scoped because {@code /brain/{tenant}/admin/...} is the
 * standard admin namespace — the {@code tenant} path-variable is
 * checked by {@code BrainAccessFilter} against the JWT.
 *
 * <p>Each pod refreshes independently. To force a cluster-wide
 * refresh, operators currently call this endpoint per pod; a
 * cluster broadcast helper is out of scope for v1.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/ai-models")
@RequiredArgsConstructor
@Slf4j
public class ModelCatalogAdminController {

    private final ModelCatalog modelCatalog;
    private final ModelDiscoveryService discoveryService;
    private final RequestAuthority authority;

    @PostMapping("/refresh")
    public ModelCatalog.RefreshResult refresh(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        log.info("ModelCatalog: manual refresh requested by tenant '{}'", tenant);
        return modelCatalog.refresh();
    }

    /**
     * Triggers an auto-discovery pass for this tenant — walks every
     * project's {@code ai.provider.<instance>.*} settings, calls each
     * backend's listing endpoint, and writes per-model YAML docs
     * under {@code _vance/model-auto/<instance>/<slug>.yaml} in the
     * project where the credentials live. After the writes finish,
     * the in-memory catalog is refreshed automatically; the caller
     * doesn't need to chain a second {@code /refresh}.
     */
    @PostMapping("/discover")
    public ModelDiscoveryService.DiscoveryResult discover(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        log.info("ModelDiscovery: manual discovery requested by tenant '{}'", tenant);
        return discoveryService.discoverForTenant(tenant);
    }
}
