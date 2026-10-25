package de.mhus.vance.shared.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

class TeamQuotaServiceTest {

    private final TeamService teamService = mock(TeamService.class);
    private final SettingService settingService = mock(SettingService.class);
    private final TeamQuotaService service = new TeamQuotaService(teamService, settingService);

    private void givenLimit(int limit) {
        when(settingService.getIntValue(
                        "acme",
                        SettingService.SCOPE_PROJECT,
                        ProjectService.TENANT_PROJECT_NAME,
                        TeamQuotaService.KEY_MAX_ACTIVE,
                        0))
                .thenReturn(limit);
    }

    private static TeamDocument team(boolean enabled) {
        return TeamDocument.builder()
                .tenantId("acme")
                .name("ops")
                .enabled(enabled)
                .build();
    }

    @Test
    void noLimit_allowsWithoutCounting() {
        givenLimit(0);

        assertThatCode(() -> service.checkOneMore("acme")).doesNotThrowAnyException();
        verify(teamService, never()).countEnabled(anyString());
    }

    @Test
    void negativeLimit_isNoLimit() {
        givenLimit(-2);

        assertThat(service.status("acme").limit()).isZero();
    }

    @Test
    void belowLimit_allows() {
        givenLimit(3);
        when(teamService.countEnabled("acme")).thenReturn(2L);

        assertThatCode(() -> service.checkOneMore("acme")).doesNotThrowAnyException();
    }

    @Test
    void atLimit_refusesWithCountAndLimit() {
        givenLimit(3);
        when(teamService.countEnabled("acme")).thenReturn(3L);

        assertThatThrownBy(() -> service.checkOneMore("acme"))
                .isInstanceOfSatisfying(TeamQuotaService.TeamQuotaExceededException.class, e -> {
                    assertThat(e.active()).isEqualTo(3L);
                    assertThat(e.limit()).isEqualTo(3);
                    assertThat(e.getMessage()).contains("Team quota reached: 3 of 3");
                });
    }

    @Test
    void enablingADisabledTeam_isChecked() {
        givenLimit(1);
        when(teamService.countEnabled("acme")).thenReturn(1L);

        assertThatThrownBy(() -> service.checkEnabledChange("acme", team(false), true))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
    }

    @Test
    void changesThatKeepTheCount_areNotChecked() {
        givenLimit(1);
        when(teamService.countEnabled("acme")).thenReturn(5L);

        assertThatCode(() -> service.checkEnabledChange("acme", team(true), true))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.checkEnabledChange("acme", team(true), false))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.checkEnabledChange("acme", team(false), false))
                .doesNotThrowAnyException();
    }

    @Test
    void status_reportsCountLimitAndOvershoot() {
        givenLimit(4);
        when(teamService.countEnabled("acme")).thenReturn(5L);

        TeamQuotaService.Status status = service.status("acme");

        assertThat(status).isEqualTo(new TeamQuotaService.Status(5L, 4));
        assertThat(status.overLimit()).isTrue();
    }
}
