package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shell refuses at the team quota like the product surfaces — the
 * service enforces it — and steps past it only on {@code --ignore-quota},
 * which it passes as {@code enforceQuota = false}.
 */
class TeamCommandsQuotaTest {

    private final TeamService teamService = mock(TeamService.class);
    private final TeamQuotaService quotaService = mock(TeamQuotaService.class);
    private final TeamCommands commands = new TeamCommands(teamService, quotaService);

    private final TeamDocument ops =
            TeamDocument.builder().tenantId("acme").name("ops").enabled(false).build();

    @BeforeEach
    void setUp() {
        when(teamService.create(anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(ops);
        when(teamService.update(anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(ops);
        when(quotaService.status("acme")).thenReturn(new TeamQuotaService.Status(4, 3));
    }

    private static TeamQuotaService.TeamQuotaExceededException full() {
        return new TeamQuotaService.TeamQuotaExceededException(3, 3);
    }

    @Test
    void create_refusesAtTheLimit() {
        when(teamService.create(eq("acme"), eq("ops"), any(), any(), eq(true))).thenThrow(full());

        String out = commands.create("acme", "ops", null, null, false);

        assertThat(out).startsWith("Refused:").contains("3/3").contains("team.quota.maxActive");
    }

    @Test
    void create_ignoreQuotaGoesThroughAndShowsTheOvershoot() {
        String out = commands.create("acme", "ops", null, null, true);

        assertThat(out).startsWith("Created:").contains("4/3 teams (over the limit)");
        verify(teamService).create(eq("acme"), eq("ops"), any(), any(), eq(false));
    }

    @Test
    void enabling_atTheLimit_isRefused() {
        when(teamService.update(eq("acme"), eq("ops"), any(), eq(true), any(), eq(true)))
                .thenThrow(full());

        String out = commands.update("acme", "ops", null, true, null, false);

        assertThat(out).startsWith("Refused:");
    }

    @Test
    void enabling_ignoreQuota_updatesWithoutEnforcement() {
        String out = commands.update("acme", "ops", null, true, null, true);

        assertThat(out).startsWith("Updated:");
        verify(teamService).update(eq("acme"), eq("ops"), any(), eq(true), any(), eq(false));
    }
}
