package de.mhus.vance.brain.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.users.TeamCreateRequest;
import de.mhus.vance.api.users.TeamUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The two places the admin REST makes a team counted: create, and enabling a disabled one. */
class TeamAdminControllerQuotaTest {

    private TeamService teams;
    private TeamQuotaService quota;
    private TeamAdminController controller;
    private final HttpServletRequest http = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        teams = mock(TeamService.class);
        quota = mock(TeamQuotaService.class);
        controller = new TeamAdminController(teams, mock(RequestAuthority.class), quota);
    }

    private static TeamQuotaService.TeamQuotaExceededException full() {
        return new TeamQuotaService.TeamQuotaExceededException(3, 3);
    }

    @Test
    void create_atTheLimit_createsNothing() {
        doThrow(full()).when(quota).checkOneMore("acme");
        TeamCreateRequest request = TeamCreateRequest.builder().name("ops").build();

        assertThatThrownBy(() -> controller.create("acme", request, http))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
        verify(teams, never()).create(anyString(), anyString(), any(), any());
    }

    @Test
    void enabling_atTheLimit_changesNothing() {
        TeamDocument disabled = TeamDocument.builder()
                .tenantId("acme")
                .name("ops")
                .enabled(false)
                .build();
        when(teams.findByTenantAndName("acme", "ops")).thenReturn(Optional.of(disabled));
        doThrow(full()).when(quota).checkEnabledChange("acme", disabled, true);
        TeamUpdateRequest request = TeamUpdateRequest.builder().enabled(true).build();

        assertThatThrownBy(() -> controller.update("acme", "ops", request, http))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
        verify(teams, never()).update(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void anUpdateThatDoesNotEnable_isNotChecked() {
        when(teams.update("acme", "ops", "Ops", null, null))
                .thenReturn(TeamDocument.builder().name("ops").build());
        TeamUpdateRequest request = TeamUpdateRequest.builder().title("Ops").build();

        controller.update("acme", "ops", request, http);

        verify(quota, never()).checkEnabledChange(anyString(), any(), anyBoolean());
    }
}
