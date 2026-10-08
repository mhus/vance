package de.mhus.vance.anus.maintenance;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.UserHubSweeper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The shell's {@link UserHubSweeper}: hands the hub to
 * {@link ProjectMaintenanceService#deleteUserHub}, the same sweep
 * {@code user delete} runs through {@link HubProjectUserDataHandler}.
 *
 * <p>Consumed by {@code TrillianProjectDataHandler}: deleting a project ends
 * every Trillian started in it, and a Trillian's hub is where its loop,
 * schedules and goals live — removing only the account would strand all of
 * that under a name the next Trillian may be minted with.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceUserHubSweeper implements UserHubSweeper {

    private final ProjectMaintenanceService projectMaintenanceService;
    private final ProjectService projectService;

    @Override
    public boolean sweepUserHub(String tenantId, String hubProjectName) {
        if (!projectService.existsByTenantAndName(tenantId, hubProjectName)) {
            return true;
        }
        return projectMaintenanceService.deleteUserHub(tenantId, hubProjectName).complete();
    }
}
