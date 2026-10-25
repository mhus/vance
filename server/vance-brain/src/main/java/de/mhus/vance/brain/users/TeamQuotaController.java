package de.mhus.vance.brain.users;

import de.mhus.vance.api.users.TeamQuotaDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.team.TeamQuotaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's team quota — count and limit.
 *
 * <p>Its own path rather than {@code /admin/teams/quota}: that one is
 * {@code GET /admin/teams/{name}} for a team called {@code quota}. Same gate
 * as the team list.
 */
@RestController
@RequiredArgsConstructor
public class TeamQuotaController {

    private final TeamQuotaService quotaService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/admin/team-quota")
    public TeamQuotaDto quota(@PathVariable("tenant") String tenant, HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.WRITE);
        TeamQuotaService.Status status = quotaService.status(tenant);
        return TeamQuotaDto.builder()
                .active(status.active())
                .limit(status.limit())
                .build();
    }
}
