package de.mhus.vance.shared.team;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.permission.TeamMembership;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Team lifecycle and lookup — the one entry point to team data.
 *
 * <p>Also answers {@link TeamMembership} for the permission layer, which needs
 * a user's teams to decide inbox visibility (R5) without depending on this
 * service's other surface.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService implements TeamMembership {

    private static final String F_TENANT_ID = "tenantId";
    private static final String F_NAME = "name";
    private static final String F_MEMBERS = "members";
    private static final String F_TITLE = "title";
    private static final String F_ENABLED = "enabled";

    private final TeamRepository repository;

    /** For the atomic member updates — a read-modify-write would lose concurrent joins. */
    private final MongoTemplate mongoTemplate;

    /** Name-keyed stores (role assignments) that must follow a team's lifecycle. */
    private final ObjectProvider<TeamLifecycleListener> lifecycleListeners;

    public Optional<TeamDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<TeamDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /** Enabled teams of {@code tenantId} — what the team quota counts. */
    public long countEnabled(String tenantId) {
        return repository.countByTenantIdAndEnabledNot(tenantId, false);
    }

    /** Teams that include the given username. */
    public List<TeamDocument> byMember(String tenantId, String username) {
        return repository.findByTenantIdAndMembersContaining(tenantId, username);
    }

    @Override
    public List<String> teamNamesOf(String tenantId, String username) {
        return byMember(tenantId, username).stream().map(TeamDocument::getName).toList();
    }

    /**
     * Creates a team inside {@code tenantId}. {@code members} may be empty.
     * Throws {@link TeamAlreadyExistsException} if a team with the same
     * {@code name} already lives in that tenant.
     */
    public TeamDocument create(String tenantId, String name, @Nullable String title, @Nullable List<String> members) {
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new TeamAlreadyExistsException("Team '" + name + "' already exists in tenant '" + tenantId + "'");
        }
        TeamDocument team = TeamDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .title(title)
                .members(members == null ? new ArrayList<>() : new ArrayList<>(members))
                .enabled(true)
                .build();
        TeamDocument saved = repository.save(team);
        // Best-effort: the team exists by now, so there is nothing to abort.
        lifecycleListeners.orderedStream().forEach(listener -> {
            try {
                listener.onTeamCreated(saved);
            } catch (RuntimeException e) {
                log.warn(
                        "Team-lifecycle listener {} failed for created team '{}' in tenant '{}'",
                        listener.getClass().getSimpleName(),
                        name,
                        tenantId,
                        e);
            }
        });
        log.info(
                "Created team tenantId='{}' name='{}' id='{}' members={}",
                saved.getTenantId(),
                saved.getName(),
                saved.getId(),
                saved.getMembers().size());
        return saved;
    }

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are
     * immutable. {@code null} fields mean "leave as is"; {@code members}
     * replaces the list wholesale when non-null.
     *
     * <p>One targeted {@code $set} of exactly the given fields — never a
     * save of the whole document, which would overwrite a member that
     * {@link #addMember}/{@link #removeMember} changed in the meantime.
     */
    public TeamDocument update(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable Boolean enabled,
            @Nullable List<String> members) {
        Update update = new Update();
        if (title != null) {
            update.set(F_TITLE, title);
        }
        if (enabled != null) {
            update.set(F_ENABLED, enabled);
        }
        if (members != null) {
            update.set(F_MEMBERS, new ArrayList<>(members));
        }
        TeamDocument saved;
        if (update.getUpdateObject().isEmpty()) {
            saved = repository
                    .findByTenantIdAndName(tenantId, name)
                    .orElseThrow(() ->
                            new TeamNotFoundException("Team '" + name + "' not found in tenant '" + tenantId + "'"));
        } else {
            saved = mongoTemplate.findAndModify(
                    byName(tenantId, name),
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    TeamDocument.class);
            if (saved == null) {
                throw new TeamNotFoundException("Team '" + name + "' not found in tenant '" + tenantId + "'");
            }
        }
        log.info(
                "Updated team tenantId='{}' name='{}' enabled={} members={}",
                saved.getTenantId(),
                saved.getName(),
                saved.isEnabled(),
                saved.getMembers().size());
        return saved;
    }

    /**
     * Adds {@code username} to the team's members in one atomic update
     * ({@code $addToSet}). Returns {@code false} when they already were a
     * member. The caller checks that the user exists.
     *
     * @throws TeamNotFoundException if the team does not exist
     */
    public boolean addMember(String tenantId, String name, String username) {
        UpdateResult result = mongoTemplate.updateFirst(
                byName(tenantId, name), new Update().addToSet(F_MEMBERS, username), TeamDocument.class);
        requireMatched(result, tenantId, name);
        boolean added = result.getModifiedCount() > 0;
        if (added) {
            log.info("Added member tenantId='{}' team='{}' user='{}'", tenantId, name, username);
        }
        return added;
    }

    /**
     * Removes {@code username} from the team's members in one atomic update
     * ({@code $pull}). Returns {@code false} when they were not a member.
     *
     * @throws TeamNotFoundException if the team does not exist
     */
    public boolean removeMember(String tenantId, String name, String username) {
        UpdateResult result = mongoTemplate.updateFirst(
                byName(tenantId, name), new Update().pull(F_MEMBERS, username), TeamDocument.class);
        requireMatched(result, tenantId, name);
        boolean removed = result.getModifiedCount() > 0;
        if (removed) {
            log.info("Removed member tenantId='{}' team='{}' user='{}'", tenantId, name, username);
        }
        return removed;
    }

    private static Query byName(String tenantId, String name) {
        return new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
    }

    private static void requireMatched(UpdateResult result, String tenantId, String name) {
        if (result.getMatchedCount() == 0) {
            throw new TeamNotFoundException("Team '" + name + "' not found in tenant '" + tenantId + "'");
        }
    }

    /**
     * Deletes a team after every {@link TeamLifecycleListener} has released
     * what it holds under the name — the team's role assignments above all.
     */
    public void delete(String tenantId, String name) {
        TeamDocument team = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new TeamNotFoundException("Team '" + name + "' not found in tenant '" + tenantId + "'"));
        // Before the document goes: a failing role store aborts the delete
        // rather than leaving roles behind for the next team of that name.
        lifecycleListeners.orderedStream().forEach(l -> l.onTeamDeleted(tenantId, name));
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
