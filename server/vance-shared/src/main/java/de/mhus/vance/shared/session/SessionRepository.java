package de.mhus.vance.shared.session;

import de.mhus.vance.api.session.SessionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SessionDocument}. Package-private — the
 * {@link SessionService} is the single outside-facing API, and the
 * atomic-binding operations use {@code MongoTemplate} directly.
 */
interface SessionRepository extends MongoRepository<SessionDocument, String> {

    Optional<SessionDocument> findBySessionId(String sessionId);

    List<SessionDocument> findByTenantId(String tenantId);

    List<SessionDocument> findByTenantIdAndUserId(String tenantId, String userId);

    List<SessionDocument> findByTenantIdAndProjectId(String tenantId, String projectId);

    List<SessionDocument> findByTenantIdAndUserIdAndProjectId(
            String tenantId, String userId, String projectId);

    /**
     * Lookup for system-owned sessions by their stable {@code displayName}.
     * The scheduler uses this to find (or recognise) its dedicated
     * {@code _scheduler_<name>} session — see
     * {@code specification/scheduler.md} §6.
     */
    Optional<SessionDocument> findFirstByTenantIdAndProjectIdAndDisplayNameAndSystem(
            String tenantId, String projectId, String displayName, boolean system);

    /**
     * Variant that filters out terminal statuses (typically CLOSED +
     * ARCHIVED) — used when a caller wants the <i>active</i> system
     * session for a slot, not a zombie row left behind by a previous
     * lifecycle. Without this, {@link #findFirstByTenantIdAndProjectIdAndDisplayNameAndSystem}
     * keeps returning the old terminal row and any "recreate" logic
     * loops indefinitely.
     */
    Optional<SessionDocument> findFirstByTenantIdAndProjectIdAndDisplayNameAndSystemAndStatusNotIn(
            String tenantId, String projectId, String displayName, boolean system,
            Collection<SessionStatus> excludedStatuses);
}
