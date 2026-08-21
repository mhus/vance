package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.brain.project.ProjectEnginesStartRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Provisions a project when it comes up on this pod.
 *
 * <p>This is the trigger that had to be synchronous with the project
 * rather than left to the periodic check. „Only active while the project
 * is active" cuts both ways: a freshly created project nobody opens would
 * otherwise never get its kits, and neither would one that slept for
 * months. Both are the same event.
 *
 * <p>Hangs off the same seam as the scheduler
 * ({@code UrsaSchedulerProjectLifecycleListener}) — the project-lifecycle
 * service is the canonical „this project is live here".
 *
 * <p>No stop counterpart, deliberately. Provisioning owns nothing that has
 * to be torn down; what it installed are ordinary project documents that
 * outlive the pod.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningLifecycleListener {

    private final KitProvisioningService provisioningService;

    @EventListener
    public void onStart(ProjectEnginesStartRequested event) {
        try {
            provisioningService.provision(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            // The service already absorbs per-entry and per-kit failures, so
            // reaching this means something more basic went wrong. Still not
            // rethrown: a project must be able to start without its kits.
            log.error("Kit provisioning failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }
}
