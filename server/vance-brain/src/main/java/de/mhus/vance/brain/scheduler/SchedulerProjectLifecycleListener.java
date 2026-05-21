package de.mhus.vance.brain.scheduler;

import de.mhus.vance.brain.project.ProjectEnginesStartRequested;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wires scheduler activation/teardown to the project lifecycle. The
 * project-lifecycle service is the canonical seam for "this project is
 * live on this pod" — see
 * {@link de.mhus.vance.brain.project.ProjectLifecycleService}.
 *
 * <p>Scheduler state is in-memory and pod-local. Projects whose
 * scheduler triggers must keep firing across pod death are tagged
 * {@link de.mhus.vance.shared.project.LifecycleType#PERMANENT} by the
 * user — the Boot-Self-Pull and the Cluster-Master Distributor (see
 * {@code specification/cluster-project-management.md}) keep them
 * placed on a live pod.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerProjectLifecycleListener {

    private final SchedulerService schedulerService;

    @EventListener
    public void onStart(ProjectEnginesStartRequested event) {
        try {
            schedulerService.bootstrapProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("Scheduler bootstrap failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }

    @EventListener
    public void onStop(ProjectEnginesStopRequested event) {
        try {
            schedulerService.unloadProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("Scheduler unload failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }
}
