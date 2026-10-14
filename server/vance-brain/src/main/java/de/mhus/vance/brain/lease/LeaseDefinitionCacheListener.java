package de.mhus.vance.brain.lease;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.lease.LeaseDefinitionLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cache invalidation for {@link LeaseDefinitionLoader}: a write under
 * {@code _vance/config/lease/} (local or routed from another pod) drops
 * the cached definition so the next acquire reads the fresh layers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaseDefinitionCacheListener {

    private final LeaseDefinitionLoader loader;

    @EventListener
    public void onDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (!path.startsWith(LeaseDefinitionLoader.PATH_PREFIX) || !path.endsWith(LeaseDefinitionLoader.PATH_SUFFIX)) {
            return;
        }
        String name = path.substring(
                LeaseDefinitionLoader.PATH_PREFIX.length(), path.length() - LeaseDefinitionLoader.PATH_SUFFIX.length());
        try {
            loader.invalidate(event.tenantId(), event.projectId(), name);
            log.debug("lease definition cache invalidated: '{}' for {}/{}", name, event.tenantId(), event.projectId());
        } catch (RuntimeException e) {
            log.warn("lease definition cache invalidation failed for '{}': {}", name, e.toString());
        }
    }
}
