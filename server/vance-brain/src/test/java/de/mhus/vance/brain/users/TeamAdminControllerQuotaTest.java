package de.mhus.vance.brain.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.users.TeamCreateRequest;
import de.mhus.vance.api.users.TeamUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.team.TeamQuotaService;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin REST goes through the enforcing {@link TeamService} methods, and
 * the service's refusal reaches the caller unchanged — 409 by
 * {@link TeamQuotaExceptionAdvice}.
 */
class TeamAdminControllerQuotaTest {

    private TeamService teams;
    private TeamAdminController controller;
    private final HttpServletRequest http = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        teams = mock(TeamService.class);
        controller = new TeamAdminController(teams, mock(RequestAuthority.class));
    }

    private static TeamQuotaService.TeamQuotaExceededException full() {
        return new TeamQuotaService.TeamQuotaExceededException(3, 3);
    }

    @Test
    void create_refusalOfTheServicePropagates() {
        when(teams.create(eq("acme"), eq("ops"), any(), any())).thenThrow(full());
        TeamCreateRequest request = TeamCreateRequest.builder().name("ops").build();

        assertThatThrownBy(() -> controller.create("acme", request, http))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
    }

    @Test
    void enabling_refusalOfTheServicePropagates() {
        when(teams.update("acme", "ops", null, true, null)).thenThrow(full());
        TeamUpdateRequest request = TeamUpdateRequest.builder().enabled(true).build();

        assertThatThrownBy(() -> controller.update("acme", "ops", request, http))
                .isInstanceOf(TeamQuotaService.TeamQuotaExceededException.class);
    }
}
