package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceReport;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceService;
import java.util.List;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The confirmation gate. Its whole job is to make the hand pause on the
 * <em>right</em> project, which is why it asks for the name and not for a
 * yes — and why a headless run has to carry the name in {@code --confirm}
 * rather than being waved through.
 */
class ProjectCommandsConfirmationTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectMaintenanceService maintenanceService =
            mock(ProjectMaintenanceService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LineReader> lineReader = mock(ObjectProvider.class);

    private final ProjectCommands commands = new ProjectCommands(
            projectService, mock(AnusBrainClient.class), maintenanceService, lineReader);

    @Test
    void delete_refuses_whenThereIsNoTerminalAndNoConfirmFlag() {
        when(lineReader.getIfAvailable()).thenReturn(null);

        String out = commands.delete("acme", "p1", null, false);

        assertThat(out).contains("--confirm p1");
        verify(maintenanceService, never()).delete("acme", "p1", false);
    }

    @Test
    void delete_refuses_whenTheTypedNameDoesNotMatch() {
        String out = commands.delete("acme", "p1", "p2", false);

        assertThat(out).contains("did not match");
        verify(maintenanceService, never()).delete("acme", "p1", false);
    }

    @Test
    void delete_proceeds_whenTheConfirmFlagCarriesTheName() {
        when(maintenanceService.delete("acme", "p1", false)).thenReturn(report("p1"));

        String out = commands.delete("acme", "p1", "p1", false);

        verify(maintenanceService).delete("acme", "p1", false);
        assertThat(out).contains("Deleted project 'p1'");
    }

    @Test
    void delete_acceptsTheTypedName_fromTheTerminal() {
        LineReader reader = mock(LineReader.class);
        when(lineReader.getIfAvailable()).thenReturn(reader);
        // Trimmed: a trailing space is a typing artefact, not a wrong answer.
        when(reader.readLine(org.mockito.ArgumentMatchers.anyString())).thenReturn("p1 ");
        when(maintenanceService.delete("acme", "p1", false)).thenReturn(report("p1"));

        commands.delete("acme", "p1", null, false);

        verify(maintenanceService).delete("acme", "p1", false);
    }

    @Test
    void rename_reportsBlockers_withoutClaimingAnythingHappened() {
        when(maintenanceService.rename("acme", "p1", "p2", false))
                .thenThrow(new ProjectMaintenanceService.RenameBlockedException(
                        List.of("workspace: folder already exists")));

        String out = commands.rename("acme", "p1", "p2", "p1", false);

        assertThat(out).contains("nothing was written")
                .contains("folder already exists");
    }

    private static ProjectMaintenanceReport report(String project) {
        return new ProjectMaintenanceReport("acme", project,
                ProjectMaintenanceReport.Operation.DELETE, List.of(), List.of());
    }
}
