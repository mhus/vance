package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.brain.project.ProjectEnginesStartRequested;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wires hook activation/teardown to the project lifecycle — analogous
 * to {@code UrsaSchedulerProjectLifecycleListener}. The project-lifecycle
 * service is the canonical "this project is live on this pod" seam.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookProjectLifecycleListener {

    private final UrsaHookService ursaHookService;

    @EventListener
    public void onStart(ProjectEnginesStartRequested event) {
        try {
            ursaHookService.bootstrapProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("Hook bootstrap failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }

    @EventListener
    public void onStop(ProjectEnginesStopRequested event) {
        try {
            ursaHookService.unloadProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("Hook unload failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }
}
