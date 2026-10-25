package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shell refuses at the team quota like the product surfaces, and steps
 * past it only on {@code --ignore-quota}.
 */
class TeamCommandsQuotaTest {

    private final TeamService teamService = mock(TeamService.class);
    private final TeamQuotaService quotaService = mock(TeamQuotaService.class);
    private final TeamCommands commands = new TeamCommands(teamService, quotaService);

    private final TeamDocument ops =
            TeamDocument.builder().tenantId("acme").name("ops").enabled(false).build();

    @BeforeEach
    void setUp() {
        when(teamService.create(anyString(), anyString(), any(), any())).thenReturn(ops);
        when(teamService.update(anyString(), anyString(), any(), any(), any())).thenReturn(ops);
        when(teamService.findByTenantAndName("acme", "ops")).thenReturn(Optional.of(ops));
        when(quotaService.status("acme")).thenReturn(new TeamQuotaService.Status(4, 3));
    }

    private void atLimit() {
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quotaService)
                .checkOneMore("acme");
    }

    @Test
    void create_refusesAtTheLimit() {
        atLimit();

        String out = commands.create("acme", "ops", null, null, false);

        assertThat(out).startsWith("Refused:").contains("3/3").contains("team.quota.maxActive");
        verify(teamService, never()).create(anyString(), anyString(), any(), any());
    }

    @Test
    void create_ignoreQuotaGoesThroughAndShowsTheOvershoot() {
        atLimit();

        String out = commands.create("acme", "ops", null, null, true);

        assertThat(out).startsWith("Created:").contains("4/3 teams (over the limit)");
    }

    @Test
    void enabling_atTheLimit_isRefused() {
        doThrow(new TeamQuotaService.TeamQuotaExceededException(3, 3))
                .when(quotaService)
                .checkEnabledChange("acme", ops, true);

        String out = commands.update("acme", "ops", null, true, null, false);

        assertThat(out).startsWith("Refused:");
        verify(teamService, never()).update(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void anUpdateThatDoesNotEnable_isNotChecked() {
        commands.update("acme", "ops", "Ops", null, null, false);

        verify(quotaService, never()).checkEnabledChange(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
