package de.mhus.vance.brain.project;

import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Re-derives {@code ProjectDocument.ownerRequired} when a document that decides
 * it appears or disappears.
 *
 * <p><b>Listens to the raw event, not the routed one.</b> The router exists for
 * cache coherence and answers "which pods hold a cache for this project"; this
 * question is different — "does this project, on any pod, now need an owner" —
 * and the answer must be recorded even when no pod holds anything. Taking the
 * raw event makes ownership irrelevant here instead of a precondition, which is
 * the same call the kit-provisioning listener makes for the same reason.
 *
 * <p><b>{@code @Async} is not an optimisation.</b> The raw event is published
 * inside the document write path; this listener queries and may write, and
 * neither belongs on the publisher's thread.
 *
 * <p>Both directions matter, which is why this reacts to {@code Deleted} too:
 * removing the last scheduler of a project must release it from the
 * keep-alive set, or the cluster keeps paying for a project that has nothing
 * left to run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectOwnerRequirementListener {

    private final ProjectOwnerRequirementService requirementService;

    @Async
    @EventListener
    public void onDocumentChanged(DocumentChangedEvent event) {
        if (!ProjectOwnerRequirementService.isActivationSourcePath(event.path())) return;
        warnIfTenantLevel(event);
        try {
            requirementService.recompute(event.tenantId(), event.projectId());
        } catch (RuntimeException e) {
            // Swallow: a failed re-derivation must not travel back into
            // whatever wrote the document. The next change to any activation
            // source recomputes, and the value only decides whether a
            // recovery tick picks the project up.
            log.warn("Owner-requirement re-derivation failed for {}/{} after '{}': {}",
                    event.tenantId(), event.projectId(), event.path(), e.toString());
        }
    }

    /**
     * Says out loud what a tenant-level scheduler or hook actually does.
     *
     * <p>Both loaders resolve through the cascade {@code project → _tenant}, so
     * an entry written to {@code _tenant} applies to every project of the
     * tenant. The derivation below deliberately does <b>not</b> follow that
     * cascade: {@code _tenant} is podless and cannot itself be kept on a pod,
     * and following it would mean a single document pinning an entire tenant's
     * projects to pods forever — the same "pin everything" outcome that kit
     * provisioning was excluded for, only larger.
     *
     * <p>The consequence is real and easy to trip over, so it is stated rather
     * than hidden: the entry fires in every project that happens to be up, and
     * wakes none that are not. A cron that must run whether or not anyone is
     * looking belongs in the project it should run in.
     */
    private static void warnIfTenantLevel(DocumentChangedEvent event) {
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) return;
        log.warn("'{}' was written to {}/_tenant: it will fire in every project that is "
                        + "currently running and will not bring a dormant one up. "
                        + "Put it in the project it should run in, or pin that project with "
                        + "lifecycleType=PERMANENT.",
                event.path(), event.tenantId());
    }
}
