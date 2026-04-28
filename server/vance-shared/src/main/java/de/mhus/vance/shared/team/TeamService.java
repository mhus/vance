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

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are
     * immutable. {@code null} fields mean "leave as is"; {@code members}
     * replaces the list wholesale when non-null.
     */
    public TeamDocument update(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable Boolean enabled,
            @Nullable List<String> members) {
        TeamDocument team = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new TeamNotFoundException(
                        "Team '" + name + "' not found in tenant '" + tenantId + "'"));
        if (title != null) {
            team.setTitle(title);
        }
        if (enabled != null) {
            team.setEnabled(enabled);
        }
        if (members != null) {
            team.setMembers(new ArrayList<>(members));
        }
        TeamDocument saved = repository.save(team);
        log.info("Updated team tenantId='{}' name='{}' enabled={} members={}",
                saved.getTenantId(), saved.getName(), saved.isEnabled(), saved.getMembers().size());
        return saved;
    }

    public void delete(String tenantId, String name) {
        TeamDocument team = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new TeamNotFoundException(
                        "Team '" + name + "' not found in tenant '" + tenantId + "'"));
        repository.delete(team);
        log.info("Deleted team tenantId='{}' name='{}'", tenantId, name);
    }

    public static class TeamAlreadyExistsException extends RuntimeException {
        public TeamAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class TeamNotFoundException extends RuntimeException {
        public TeamNotFoundException(String message) {
            super(message);
        }
    }
}
