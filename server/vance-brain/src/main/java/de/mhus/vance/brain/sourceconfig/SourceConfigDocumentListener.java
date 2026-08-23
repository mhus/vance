package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drops a factory's cached instances when one of its configuration documents
 * changes, whatever wrote it — Cortex, WebDAV, a kit installer, a tool.
 *
 * <p>A change in the tenant-wide project drops the whole tenant, because the
 * configuration cascades into every project while the cache is keyed per
 * project.
 *
 * <p><b>This accelerates the TTL, it does not replace it.</b> The routed event
 * reaches every live pod only for writes to the {@code _tenant} project; a
 * write inside a normal project reaches the writing pod and the lease holder,
 * and a third pod that lazily built a scope for that project hears nothing.
 * The factories therefore keep their expiry, and the explicit refresh button
 * keeps its reason to exist. Configuring sources tenant-wide — which is the
 * usual thing to do — happens to be the case that does fan out.
 *
 * <p>Contract as for every routed listener: idempotent, write-free, catches its
 * own exceptions, and gated on the path prefix so the bulk of fires return at
 * the first line.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceConfigDocumentListener {

    private final List<SourceConfigCache> caches;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null || !path.startsWith("_vance/config/")) {
            return;
        }
        for (SourceConfigCache cache : caches) {
            if (!path.startsWith(cache.configPathPrefix())) {
                continue;
            }
            try {
                // A tenant-wide document is part of what every project of the
                // tenant assembles, so evicting only its own entry would leave
                // the projects that actually read it stale.
                if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
                    cache.evictTenant(event.tenantId());
                } else {
                    cache.evict(event.tenantId(), event.projectId());
                }
                log.debug("SourceConfig: evicted {} for '{}/{}' after change to '{}'",
                        cache.getClass().getSimpleName(),
                        event.tenantId(), event.projectId(), path);
            } catch (RuntimeException ex) {
                log.warn("SourceConfig: evict failed for '{}' on '{}/{}': {}",
                        path, event.tenantId(), event.projectId(), ex.toString());
            }
        }
    }
}
