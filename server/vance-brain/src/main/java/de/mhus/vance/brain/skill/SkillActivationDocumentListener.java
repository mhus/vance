package de.mhus.vance.brain.skill;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Evicts the cached tier of {@code _vance/config/skill_activation.yaml}
 * when that document changes, whatever wrote it — the mode REST endpoint,
 * Cortex, WebDAV, a kit installer.
 *
 * <p>Only the changed tier is evicted: {@link SkillActivationService}
 * caches per tier document and merges per call, so a {@code _tenant}
 * change needs no tenant-wide sweep.
 *
 * <p><b>This accelerates the TTL, it does not replace it</b> — the router
 * does not reach every pod for project or user-hub writes
 * (specification/document-change-events.md).
 *
 * <p>Contract as for every routed listener: idempotent, write-free,
 * catches its own exceptions, path check first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillActivationDocumentListener {

    private final SkillActivationService activationService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (!SkillActivationService.ACTIVATION_CONFIG_PATH.equals(event.path())) {
            return;
        }
        try {
            activationService.evictTier(event.tenantId(), event.projectId());
            log.trace("SkillActivation: evicted tier '{}/{}'", event.tenantId(), event.projectId());
        } catch (RuntimeException ex) {
            log.warn(
                    "SkillActivation: evict failed for '{}/{}': {}",
                    event.tenantId(),
                    event.projectId(),
                    ex.toString());
        }
    }
}
