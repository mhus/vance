package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.cluster.ProjectClusterService;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.anus.maintenance.MaintenanceReport;
import de.mhus.vance.anus.maintenance.ProjectMaintenanceService;
import java.util.List;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Delete and rename hand the project off its pod first.
 *
 * <p>Not politeness: a project on a pod has engines running and its workspace
 * mounted on that machine. The hand-off makes it an orderly shutdown, and it is
 * also what lets a rename carry a work area that lives on another machine's disk
 * — the holding pod snapshots it into Mongo on the way out.
 */
class ProjectCommandsDrainTest {

    private static final String HOME = "/internal/cluster/projects/home";
    private static final String HOME_BODY =
            "{\"endpoint\":\"10.0.0.7:9990\",\"nodeName\":\"pod-a\"}";

    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectMaintenanceService maintenanceService =
            mock(ProjectMaintenanceService.class);
    private final AnusBrainClient brainClient = mock(AnusBrainClient.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LineReader> lineReader = mock(ObjectProvider.class);

    /**
     * A real {@link ProjectClusterService} over the mocked client, not a mock of
     * the service: these tests are about what an operator sees, and the
     * translation from HTTP status to situation is part of that. Mocking the
     * service would leave that translation untested and turn the assertions into
     * a check of this test's own stubbing.
     */
    private final ProjectCommands commands = new ProjectCommands(
            projectService, new ProjectClusterService(brainClient), maintenanceService, lineReader);

    @Test
    void delete_drainsFirst_whenAPodHoldsTheProject() {
        givenPlacedOn();
        givenReleaseSucceeds();
        when(maintenanceService.delete("acme", "p1", false)).thenReturn(report("p1"));

        String out = commands.delete("acme", "p1", "p1", false, false);

        verify(brainClient).internalAt(
                eq("http://10.0.0.7:9990"), eq("/internal/cluster/release"), eq("POST"), any());
        assertThat(out).contains("drained from 'pod-a'").contains("Deleted project 'p1'");
    }

    @Test
    void delete_refuses_whenTheDrainFailed() {
        givenPlacedOn();
        when(brainClient.internalAt(anyString(), contains("release"), anyString(), any()))
                .thenReturn(new Response(500, "boom"));

        String out = commands.delete("acme", "p1", "p1", false, false);

        // Not knowing whether a pod is still working on the project is exactly
        // the case where proceeding is unsafe.
        assertThat(out).contains("Refusing to delete").contains("--force").contains("--no-drain");
        verify(maintenanceService, never()).delete(anyString(), anyString(), anyBoolean());
    }

    @Test
    void delete_proceedsOnForce_whenTheDrainFailed() {
        givenPlacedOn();
        when(brainClient.internalAt(anyString(), contains("release"), anyString(), any()))
                .thenReturn(new Response(500, "boom"));
        when(maintenanceService.delete("acme", "p1", true)).thenReturn(report("p1"));

        String out = commands.delete("acme", "p1", "p1", true, false);

        assertThat(out).contains("continuing because --force");
        verify(maintenanceService).delete("acme", "p1", true);
    }

    @Test
    void delete_needsNoDrain_whenNobodyHoldsTheProject() {
        when(brainClient.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(404, "no home pod"));
        when(maintenanceService.delete("acme", "p1", false)).thenReturn(report("p1"));

        String out = commands.delete("acme", "p1", "p1", false, false);

        assertThat(out).contains("nothing to drain");
        verify(brainClient, never()).internalAt(anyString(), contains("release"), anyString(), any());
        verify(maintenanceService).delete("acme", "p1", false);
    }

    @Test
    void delete_skipsTheHandOff_onNoDrain() {
        when(maintenanceService.delete("acme", "p1", false)).thenReturn(report("p1"));

        String out = commands.delete("acme", "p1", "p1", false, true);

        assertThat(out).contains("--no-drain");
        verify(brainClient, never()).internal(anyString(), anyString(), any());
    }

    @Test
    void rename_placesTheProjectAgain_underTheNewName() {
        givenPlacedOn();
        givenReleaseSucceeds();
        when(maintenanceService.rename("acme", "p1", "p2", false))
                .thenReturn(report("p1", MaintenanceReport.Operation.RENAME));
        when(brainClient.internal(contains("/internal/cluster/place"), eq("POST"), any()))
                .thenReturn(new Response(200, "{\"node\":\"pod-b\"}"));

        String out = commands.rename("acme", "p1", "p2", "p1", false, false);

        // The new name, not the old one — that is the whole point of doing it
        // after the rename rather than before.
        verify(brainClient).internal(
                contains("/internal/cluster/place"), eq("POST"), contains("\"p2\""));
        assertThat(out).contains("Placing 'p2' again");
    }

    @Test
    void rename_doesNotPlace_whatNobodyWasHolding() {
        when(brainClient.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(404, "no home pod"));
        when(maintenanceService.rename("acme", "p1", "p2", false))
                .thenReturn(report("p1", MaintenanceReport.Operation.RENAME));

        String out = commands.rename("acme", "p1", "p2", "p1", false, false);

        // Placing it here would start something the rename did not ask for.
        verify(brainClient, never()).internal(
                contains("/internal/cluster/place"), anyString(), any());
        assertThat(out).doesNotContain("Placing");
    }

    @Test
    void rename_saysHowToPlaceItAgain_whenTheRenameFailedAfterTheDrain() {
        givenPlacedOn();
        givenReleaseSucceeds();
        when(maintenanceService.rename("acme", "p1", "p2", false))
                .thenThrow(new IllegalStateException("mongo down"));

        String out = commands.rename("acme", "p1", "p2", "p1", false, false);

        // The drain already happened; leaving the operator with an unplaced
        // project and no hint would be the worst part of this failure.
        assertThat(out).contains("Rename FAILED")
                .contains("still called 'p1'")
                .contains("project claim -T acme -n p1");
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────

    private void givenPlacedOn() {
        when(brainClient.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(200, HOME_BODY));
    }

    private void givenReleaseSucceeds() {
        when(brainClient.internalAt(anyString(), contains("release"), anyString(), any()))
                .thenReturn(new Response(200, "released"));
    }

    private static MaintenanceReport report(String project) {
        return report(project, MaintenanceReport.Operation.DELETE);
    }

    private static MaintenanceReport report(
            String project, MaintenanceReport.Operation operation) {
        return new MaintenanceReport("acme", project, operation, List.of(), List.of(), true);
    }
}
