package de.mhus.vance.brain.scheduler;

import de.mhus.vance.brain.project.ProjectEnginesStartRequested;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.project.ProjectService;
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
 * <p>Also flips the project document's {@code requiresOwnerPod} flag so
 * the cluster-wide wakeup-tick can find RUNNING projects whose owner
 * died and re-bring them. Scheduler state is in-memory and pod-local,
 * so a project with active triggers must have a live home cluster —
 * otherwise its scheduled work would silently stop firing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerProjectLifecycleListener {

    private final SchedulerService schedulerService;
    private final ProjectService projectService;

    @EventListener
    public void onStart(ProjectEnginesStartRequested event) {
        try {
            schedulerService.bootstrapProject(event.tenantId(), event.projectName());
            projectService.setRequiresOwnerPod(event.tenantId(), event.projectName(), true);
        } catch (RuntimeException ex) {
            log.error("Scheduler bootstrap failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }

    @EventListener
    public void onStop(ProjectEnginesStopRequested event) {
        try {
            schedulerService.unloadProject(event.tenantId(), event.projectName());
            projectService.setRequiresOwnerPod(event.tenantId(), event.projectName(), false);
        } catch (RuntimeException ex) {
            log.error("Scheduler unload failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }
}
