package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.shared.document.DocumentChangedEvent;
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
 * <p><b>Listens to the raw event, not the routed one — and that is the
 * whole point.</b> The first version took
 * {@code RoutedDocumentChangedEvent} and never fired: the router exists
 * for <em>cache coherence</em> and therefore drops an event when nobody
 * holds a cache for that project — unknown project, unclaimed, or a
 * {@code homeNode} pointing at a pod that is no longer alive. That last
 * case is not exotic; it is the normal state of an {@code EPHEMERAL}
 * project after any restart, because nothing clears the field and the
 * boot-time self-pull only reclaims {@code PERMANENT} ones.
 *
 * <p>Provisioning is not a cache. It does not need the owning pod — it
 * needs <em>a</em> pod, and the one that served the write is exactly one.
 * Taking the raw event makes ownership irrelevant here instead of making
 * it a precondition nobody satisfies.
 *
 * <p><b>{@code @Async} is not an optimisation.</b> The raw event is
 * published inside the write path; installing a kit writes documents by
 * the dozen, and doing that on the publisher's thread would put a kit
 * install inside somebody's document save.
 *
 * <p><b>The path filter is exact, not a prefix.</b> An install writes
 * {@code _vance/kits/installed/<id>.yaml}; a prefix filter on
 * {@code _vance/kits/} would turn that into another change event, which
 * would provision again, which would write the record again. Nothing
 * would damp that loop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningDocumentListener {

    private final KitProvisioningService provisioningService;

    @Async
    @EventListener
    public void onDocumentChanged(DocumentChangedEvent event) {
        // Cheapest possible check first: the raw event fires for *every*
        // document write on this pod, not only the _vance/ ones the router
        // publishes onwards.
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
