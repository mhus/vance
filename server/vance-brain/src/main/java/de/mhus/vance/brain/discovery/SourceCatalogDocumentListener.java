package de.mhus.vance.brain.discovery;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.skill.SkillLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drops the cached discovery catalog when a manual or skill changes.
 *
 * <p>Without it the snapshot lived until the next brain restart: a manual
 * added under {@code _vance/manuals/} was invisible to {@code how_do_i}
 * even though it was on disk, which is the opposite of what an
 * author expects after saving a file. Every other YAML-backed cache in
 * the brain already listens here — this one had the invalidation hooks
 * but no caller.
 *
 * <p>Invalidation is per tenant, not per path: manuals resolve through
 * the project → {@code _tenant} → bundled cascade, so an edit at the
 * tenant level changes what every project under it sees.
 *
 * <p>Contract — the same five rules the other
 * {@code RoutedDocumentChangedEvent} listeners follow: idempotent,
 * write-free, swallows its own exceptions, path-prefix filter first, no
 * user-scoped mutations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceCatalogDocumentListener {

    private final SourceCatalogService catalogService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        String path = event.path();
        if (path == null
                || !(path.startsWith(SourceCatalogBuilder.MANUALS_PREFIX)
                        || path.startsWith(SkillLoader.SKILL_PATH_PREFIX))) {
            return;
        }
        try {
            catalogService.invalidate(event.tenantId());
            log.debug("SourceCatalogDocumentListener: catalog invalidated for tenant '{}' "
                    + "after change to '{}'", event.tenantId(), path);
        } catch (RuntimeException ex) {
            log.warn("SourceCatalogDocumentListener: invalidate failed for tenant '{}': {}",
                    event.tenantId(), ex.toString());
        }
    }
}
