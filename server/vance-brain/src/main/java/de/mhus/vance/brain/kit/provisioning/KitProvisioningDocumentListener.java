package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Provisions a project when its provisioning document changes.
 *
 * <p>The second of the three triggers: someone wrote down a source and
 * expects it to arrive, so waiting for the next tick would be the wrong
 * answer. This is a change on <em>our</em> side, which is why it may
 * install rather than only report (see
 * {@code planning/kit-ode-provisioning.md} §8.1).
 *
 * <p><b>{@code @Async} is not an optimisation.</b> The
 * {@link RoutedDocumentChangedEvent} contract requires a listener to be
 * write-free — no callbacks into {@code DocumentService} — and installing
 * a kit writes documents by the dozen. Handing off keeps the publisher's
 * thread out of that entirely.
 *
 * <p><b>The path filter is exact, not a prefix.</b> An install writes
 * {@code _vance/kits/installed/<id>.yaml}; a prefix filter on
 * {@code _vance/kits/} would turn that into another change event, which
 * would provision again, which would write the record again. The
 * dispatcher's batching would damp that loop but not end it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningDocumentListener {

    private final KitProvisioningService provisioningService;

    @Async
    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        // Cheapest possible check first: this fires for every document write
        // under _vance/ in the whole pod.
        if (!KitProvisioningLoader.PROVISIONING_PATH.equals(event.path())) return;
        try {
            log.debug("Provisioning document of {}/{} changed — provisioning",
                    event.tenantId(), event.projectId());
            provisioningService.provisionCoalesced(event.tenantId(), event.projectId());
        } catch (RuntimeException e) {
            // Listener contract rule 3: swallow. A failed provisioning must not
            // travel back into whatever wrote the document.
            log.warn("Provisioning after a document change of {}/{} failed: {}",
                    event.tenantId(), event.projectId(), e.toString(), e);
        }
    }
}
