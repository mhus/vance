package de.mhus.vance.shared.sessiongroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Session-group lifecycle and lookup — the one entry point to session-group
 * data. Groups are scoped to {@code (tenantId, projectId, userId)} and exist
 * purely for UI organisation. See {@code planning/session-groups.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionGroupService {

    private final SessionGroupRepository repository;
    private final MongoTemplate mongoTemplate;

    /** All groups of the user in the project, ordered by {@code sortIndex}. */
    public List<SessionGroupDocument> list(String tenantId, String projectId, String userId) {
        return repository.findByTenantIdAndProjectIdAndUserIdOrderBySortIndexAsc(
                tenantId, projectId, userId);
    }

    /**
     * Creates a group. New groups sort last ({@code max(sortIndex) + 1}).
     *
     * @throws SessionGroupAlreadyExistsException if the name is taken in scope
     */
    public SessionGroupDocument create(
            String tenantId, String projectId, String userId, String name, @Nullable String title) {
        if (repository.existsByTenantIdAndProjectIdAndUserIdAndName(tenantId, projectId, userId, name)) {
            throw new SessionGroupAlreadyExistsException(
                    "Session group '" + name + "' already exists for user '" + userId
                            + "' in project '" + projectId + "'");
        }
        int nextIndex = list(tenantId, projectId, userId).stream()
                .mapToInt(SessionGroupDocument::getSortIndex)
                .max()
                .orElse(-1) + 1;
        SessionGroupDocument group = SessionGroupDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .userId(userId)
                .name(name)
                .title(title)
                .sortIndex(nextIndex)
                .build();
        SessionGroupDocument saved = repository.save(group);
        log.info("Created session group tenantId='{}' projectId='{}' userId='{}' name='{}'",
                tenantId, projectId, userId, name);
        return saved;
    }

    /**
     * Renames (only the display {@code title} — {@code name} is immutable).
     *
     * @throws SessionGroupNotFoundException if the group does not exist
     */
    public SessionGroupDocument rename(
            String tenantId, String projectId, String userId, String name, @Nullable String title) {
        SessionGroupDocument group = require(tenantId, projectId, userId, name);
        group.setTitle(title);
        SessionGroupDocument saved = repository.save(group);
        log.info("Renamed session group tenantId='{}' projectId='{}' userId='{}' name='{}' title='{}'",
                tenantId, projectId, userId, name, title);
        return saved;
    }

    /**
     * Deletes a group. Its member sessions simply become ungrouped — they are
     * only referenced by the list, which is gone; the sessions themselves are
     * untouched.
     *
     * @throws SessionGroupNotFoundException if the group does not exist
     */
    public void delete(String tenantId, String projectId, String userId, String name) {
        SessionGroupDocument group = require(tenantId, projectId, userId, name);
        repository.delete(group);
        log.info("Deleted session group tenantId='{}' projectId='{}' userId='{}' name='{}'",
                tenantId, projectId, userId, name);
    }

    /**
     * Re-assigns {@code sortIndex} from the position in {@code orderedNames}.
     * Names not present in scope are ignored; groups omitted from the list keep
     * their previous index.
     */
    public void reorder(String tenantId, String projectId, String userId, List<String> orderedNames) {
        Map<String, SessionGroupDocument> byName = new HashMap<>();
        for (SessionGroupDocument g : list(tenantId, projectId, userId)) {
            byName.put(g.getName(), g);
        }
        int index = 0;
        for (String name : orderedNames) {
            SessionGroupDocument g = byName.get(name);
            if (g != null) {
                g.setSortIndex(index++);
                repository.save(g);
            }
        }
        log.info("Reordered {} session groups tenantId='{}' projectId='{}' userId='{}'",
                index, tenantId, projectId, userId);
    }

    /**
     * Moves a session into {@code groupName}, removing it from any other group
     * of the same user first (a session belongs to at most one of the user's
     * groups). A {@code null} {@code groupName} means "ungroup".
     *
     * @throws SessionGroupNotFoundException if the target group does not exist
     */
    public void assign(
            String tenantId, String projectId, String userId,
            String sessionId, @Nullable String groupName) {
        if (groupName == null) {
            unassign(tenantId, projectId, userId, sessionId);
            return;
        }
        require(tenantId, projectId, userId, groupName);
        // Remove from every group in scope, then add to the target — atomic per op.
        pullFromAll(tenantId, projectId, userId, sessionId);
        mongoTemplate.updateFirst(
                scopedQuery(tenantId, projectId, userId).addCriteria(Criteria.where("name").is(groupName)),
                new Update().addToSet("sessionIds", sessionId),
                SessionGroupDocument.class);
        log.info("Assigned session '{}' to group '{}' tenantId='{}' projectId='{}' userId='{}'",
                sessionId, groupName, tenantId, projectId, userId);
    }

    /** Removes a session from all of the user's groups in the project. */
    public void unassign(String tenantId, String projectId, String userId, String sessionId) {
        pullFromAll(tenantId, projectId, userId, sessionId);
        log.info("Unassigned session '{}' tenantId='{}' projectId='{}' userId='{}'",
                sessionId, tenantId, projectId, userId);
    }

    private void pullFromAll(String tenantId, String projectId, String userId, String sessionId) {
        mongoTemplate.updateMulti(
                scopedQuery(tenantId, projectId, userId),
                new Update().pull("sessionIds", sessionId),
                SessionGroupDocument.class);
    }

    private SessionGroupDocument require(
            String tenantId, String projectId, String userId, String name) {
        return repository.findByTenantIdAndProjectIdAndUserIdAndName(tenantId, projectId, userId, name)
                .orElseThrow(() -> new SessionGroupNotFoundException(
                        "Session group '" + name + "' not found for user '" + userId
                                + "' in project '" + projectId + "'"));
    }

    private Query scopedQuery(String tenantId, String projectId, String userId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId)
                .and("userId").is(userId));
    }

    public static class SessionGroupAlreadyExistsException extends RuntimeException {
        public SessionGroupAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class SessionGroupNotFoundException extends RuntimeException {
        public SessionGroupNotFoundException(String message) {
            super(message);
        }
    }
}
