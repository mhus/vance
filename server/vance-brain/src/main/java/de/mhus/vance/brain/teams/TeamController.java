package de.mhus.vance.brain.teams;

import de.mhus.vance.api.teams.TeamListResponse;
import de.mhus.vance.api.teams.TeamSummary;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoint for the Web-UI's team-switcher in the inbox
 * editor. v1 returns only the teams the requesting user is a
 * member of — Tenant-wide team listing is admin-territory and
 * out of scope for the inbox UI.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TeamController {

    private final TeamService teamService;

    /** Teams the current user belongs to in this tenant. */
    @GetMapping("/brain/{tenant}/teams")
    public TeamListResponse list(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        String currentUser = currentUser(httpRequest);
        List<TeamDocument> teams = teamService.byMember(tenant, currentUser);
        List<TeamSummary> dtos = new ArrayList<>();
        for (TeamDocument t : teams) {
            dtos.add(TeamSummary.builder()
                    .id(t.getId() == null ? "" : t.getId())
                    .name(t.getName())
                    .title(t.getTitle())
                    .members(t.getMembers() == null ? new ArrayList<>()
                            : new ArrayList<>(t.getMembers()))
                    .enabled(t.isEnabled())
                    .build());
        }
        return TeamListResponse.builder().teams(dtos).build();
    }

    private String currentUser(HttpServletRequest httpRequest) {
        Object u = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        return s;
    }
}
