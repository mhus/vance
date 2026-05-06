package de.mhus.vance.brain.delegate;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint that re-reads the bundled engine catalog without
 * a JVM restart. The catalog itself is system-wide (singleton bean
 * driven by {@code vance-defaults/catalog/engines.yaml}); this
 * endpoint is tenant-scoped only because all admin endpoints in
 * this codebase live under {@code /brain/{tenant}/admin/...} —
 * any tenant admin can trigger the global reload.
 *
 * <p>Use case: bundled yaml updated post-deploy, JVM still running.
 * In a production setup with rolling redeploys this is rarely
 * needed; in development / staging it is handy.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/catalog/engines")
@RequiredArgsConstructor
@Slf4j
public class EngineCatalogAdminController {

    private final EngineCatalog engineCatalog;
    private final RequestAuthority authority;

    @PostMapping("/reload")
    public Map<String, Object> reload(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);

        int previous = engineCatalog.getEntries().size();
        int current = engineCatalog.reload();
        log.info("EngineCatalog reload triggered by tenant='{}' admin: "
                        + "{} → {} entries",
                tenant, previous, current);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entries", current);
        response.put("previousEntries", previous);
        response.put("source", EngineCatalog.CATALOG_RESOURCE);
        return response;
    }
}
