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
