package de.mhus.vance.anus.maintenance;

import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one handler that delegates to another sweep, and therefore the one place
 * where a nested partial failure can be mistaken for success.
 */
class HubProjectUserDataHandlerTest {

    private final ProjectMaintenanceService projectMaintenanceService =
            mock(ProjectMaintenanceService.class);
    private final ProjectService projectService = mock(ProjectService.class);

    private final HubProjectUserDataHandler handler =
            new HubProjectUserDataHandler(projectMaintenanceService, projectService);

    @Test
    void delete_throws_whenTheHubSweepDidNotFinish() {
        hubExists();
        when(projectMaintenanceService.deleteUserHub("acme", "_user_mhus"))
                .thenReturn(report(false));

        // The nested sweep does not throw on a failed handler — it keeps the
        // project document and says so. Swallowing that would let the account
        // document go while its data is still there, which frees the login for
        // the next person to inherit.
        assertThatThrownBy(() -> handler.delete("acme", "mhus"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_user_mhus")
                .hasMessageContaining("re-run");
    }

    @Test
    void delete_reportsTheHub_whenTheSweepFinished() {
        hubExists();
        when(projectMaintenanceService.deleteUserHub("acme", "_user_mhus"))
                .thenReturn(report(true));

        assertThat(handler.delete("acme", "mhus")).isEqualTo(1);
    }

    @Test
    void delete_isANoOp_whenThereIsNoHub() {
        when(projectService.existsByTenantAndName("acme", "_user_mhus")).thenReturn(false);

        assertThat(handler.delete("acme", "mhus")).isZero();
        verify(projectMaintenanceService, never()).deleteUserHub("acme", "_user_mhus");
    }

    private void hubExists() {
        when(projectService.existsByTenantAndName("acme", "_user_mhus")).thenReturn(true);
    }

    private static MaintenanceReport report(boolean complete) {
        return new MaintenanceReport("acme", "_user_mhus", MaintenanceReport.Operation.DELETE,
                List.of(), List.of(), complete);
    }
}
