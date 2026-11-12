package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.maintenance.MaintenanceReport;
import de.mhus.vance.anus.maintenance.MaintenanceReport.Operation;
import de.mhus.vance.anus.maintenance.TenantMaintenanceService;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TenantCommandsTest {

    private final TenantService tenants = mock(TenantService.class);
    private final TenantMaintenanceService maintenance = mock(TenantMaintenanceService.class);
    private final LineReader reader = mock(LineReader.class);

    @SuppressWarnings("unchecked")
    private TenantCommands commands(LineReader available) {
        ObjectProvider<LineReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new TenantCommands(tenants, maintenance, provider);
    }

    private static MaintenanceReport done() {
        return new MaintenanceReport("748498812", "748498812", Operation.DELETE, List.of(), List.of(), true);
    }

    @Test
    void withoutConfirmationAndWithoutATerminalNothingIsDeleted() {
        String answer = commands(null).delete("748498812", null, false);

        assertThat(answer).contains("--confirm 748498812");
        verify(maintenance, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void underSudoTheReaderThrowsAndTheAnswerNamesWhatToPass() throws Exception {
        when(reader.readLine(anyString())).thenThrow(new EndOfFileException());

        assertThat(commands(reader).delete("748498812", null, false)).contains("--confirm 748498812");
        verify(maintenance, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void aWrongConfirmationDoesNothing() {
        String answer = commands(reader).delete("748498812", "748498813", false);

        assertThat(answer).contains("did not match");
        verify(maintenance, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void aTypedNameRunsTheDeleteAndForceIsPassedOn() {
        when(maintenance.delete("748498812", true)).thenReturn(done());

        commands(reader).delete("748498812", " 748498812 ", true);

        verify(maintenance).delete("748498812", true);
    }

    @Test
    void theRefusalsOfTheServiceAreReportedAsNotDone() {
        when(maintenance.delete("748498812", false))
                .thenThrow(new TenantService.TenantStillEnabledException("Tenant '748498812' is still enabled"));

        assertThat(commands(reader).delete("748498812", "748498812", false))
                .startsWith("Not done")
                .contains("still enabled");
    }

    @Test
    void inspectReportsAnUnknownTenantAsAMessage() {
        when(maintenance.inspect("999999999"))
                .thenThrow(new TenantService.TenantNotFoundException("Tenant '999999999' not found"));

        assertThat(commands(reader).inspect("999999999")).contains("not found");
    }
}
