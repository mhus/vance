package de.mhus.vance.brain.users;

import de.mhus.vance.api.users.TeamCreateRequest;
import de.mhus.vance.api.users.TeamDto;
import de.mhus.vance.api.users.TeamUpdateRequest;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin CRUD for teams.
 *
 * <p>v1 authorisation model: any authenticated user inside the tenant
 * can read and write team records. Cross-tenant probing is blocked by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter}.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/teams")
@RequiredArgsConstructor
@Slf4j
public class TeamAdminController {

    private final TeamService teamService;

    @GetMapping
    public List<TeamDto> list(@PathVariable("tenant") String tenant) {
        return teamService.all(tenant).stream()
                .sorted(Comparator.comparing(TeamDocument::getName))
                .map(TeamAdminController::toDto)
                .toList();
    }

    @GetMapping("/{name}")
    public TeamDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        return teamService.findByTenantAndName(tenant, name)
                .map(TeamAdminController::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Team '" + name + "' not found"));
    }

    @PostMapping
    public ResponseEntity<TeamDto> create(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody TeamCreateRequest request) {
        try {
            TeamDocument saved = teamService.create(
                    tenant, request.getName(), request.getTitle(), request.getMembers());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (TeamService.TeamAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public TeamDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody TeamUpdateRequest request) {
        try {
            TeamDocument saved = teamService.update(
                    tenant,
                    name,
                    request.getTitle(),
                    request.getEnabled(),
                    request.getMembers());
            return toDto(saved);
        } catch (TeamService.TeamNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        try {
            teamService.delete(tenant, name);
            return ResponseEntity.noContent().build();
        } catch (TeamService.TeamNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static TeamDto toDto(TeamDocument t) {
        return TeamDto.builder()
                .name(t.getName())
                .title(t.getTitle())
                .members(new ArrayList<>(t.getMembers()))
                .enabled(t.isEnabled())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
