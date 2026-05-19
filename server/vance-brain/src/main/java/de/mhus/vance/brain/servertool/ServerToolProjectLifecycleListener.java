package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.project.ProjectEnginesStartRequested;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wires {@link ServerToolRegistry} to the project lifecycle. The
 * registry bootstraps when a project's engines come online and tears
 * down when they stop — mirrors the scheduler listener pattern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServerToolProjectLifecycleListener {

    private final ServerToolRegistry registry;

    @EventListener
    public void onStart(ProjectEnginesStartRequested event) {
        try {
            registry.bootstrapProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("ServerToolRegistry bootstrap failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }

    @EventListener
    public void onStop(ProjectEnginesStopRequested event) {
        try {
            registry.unloadProject(event.tenantId(), event.projectName());
        } catch (RuntimeException ex) {
            log.error("ServerToolRegistry unload failed for project '{}/{}': {}",
                    event.tenantId(), event.projectName(), ex.toString(), ex);
        }
    }
}
