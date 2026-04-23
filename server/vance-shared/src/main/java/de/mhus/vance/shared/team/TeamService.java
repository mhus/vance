package de.mhus.vance.shared.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Team lifecycle and lookup — the one entry point to team data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository repository;

    public Optional<TeamDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<TeamDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /** Teams that include the given username. */
    public List<TeamDocument> byMember(String tenantId, String username) {
        return repository.findByTenantIdAndMembersContaining(tenantId, username);
    }

    /**
     * Creates a team inside {@code tenantId}. {@code members} may be empty.
     * Throws {@link TeamAlreadyExistsException} if a team with the same
     * {@code name} already lives in that tenant.
     */
    public TeamDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable List<String> members) {
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new TeamAlreadyExistsException(
                    "Team '" + name + "' already exists in tenant '" + tenantId + "'");
        }
        TeamDocument team = TeamDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .title(title)
                .members(members == null ? new ArrayList<>() : new ArrayList<>(members))
                .enabled(true)
                .build();
        TeamDocument saved = repository.save(team);
        log.info("Created team tenantId='{}' name='{}' id='{}' members={}",
                saved.getTenantId(), saved.getName(), saved.getId(), saved.getMembers().size());
        return saved;
    }

    public static class TeamAlreadyExistsException extends RuntimeException {
        public TeamAlreadyExistsException(String message) {
            super(message);
        }
    }
}
