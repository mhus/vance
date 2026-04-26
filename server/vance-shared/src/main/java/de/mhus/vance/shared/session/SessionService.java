package de.mhus.vance.shared.session;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.ws.ClientType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Session lifecycle + connection locking.
 *
 * <p>All mutation methods that race against other pods go through
 * {@link MongoTemplate#updateFirst} with a conditional {@link Query} so the
 * „only one pod/connection may own a session" invariant is enforced at the
 * database layer, not in application memory. {@link #tryBind} is the canonical
 * acquisition call; {@link #heartbeat} refreshes the lease; {@link #unbind}
 * releases it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private static final String SESSION_ID_PREFIX = "sess_";

    /** Field names — kept in one place so the conditional queries don't drift. */
    private static final String F_SESSION_ID = "sessionId";
    private static final String F_STATUS = "status";
    private static final String F_BOUND_CONNECTION = "boundConnectionId";
    private static final String F_LAST_ACTIVITY = "lastActivityAt";
    private static final String F_CHAT_PROCESS_ID = "chatProcessId";

    private final SessionRepository repository;
    private final MongoTemplate mongoTemplate;

    // ---------------------------------------------------------------- reads

    public Optional<SessionDocument> findBySessionId(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    public List<SessionDocument> listForUser(String tenantId, String userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId);
    }

    public List<SessionDocument> listForUserAndProject(
            String tenantId, String userId, String projectId) {
        return repository.findByTenantIdAndUserIdAndProjectId(tenantId, userId, projectId);
    }

    // ------------------------------------------------------------- creation

    /**
     * Creates a new session in {@link SessionStatus#OPEN}, scoped to
     * {@code projectId}. No connection is bound yet — call {@link #tryBind}
     * immediately after if the caller is about to hold the connection.
     */
    public SessionDocument create(
            String tenantId,
            String userId,
            String projectId,
            @Nullable String displayName,
            ClientType clientType,
            String clientVersion) {
        Instant now = Instant.now();
        SessionDocument doc = SessionDocument.builder()
                .sessionId(newSessionId())
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .displayName(displayName)
                .clientType(clientType)
                .clientVersion(clientVersion)
                .boundConnectionId(null)
                .status(SessionStatus.OPEN)
                .createdAt(now)
                .lastActivityAt(now)
                .build();
        SessionDocument saved = repository.save(doc);
        log.info("Created session sessionId='{}' tenant='{}' user='{}' project='{}'",
                saved.getSessionId(), saved.getTenantId(), saved.getUserId(), saved.getProjectId());
        return saved;
    }

    // --------------------------------------------------------- atomic locks

    /**
     * Atomically claims the connection lock on {@code sessionId}. Succeeds only
     * if the session is {@link SessionStatus#OPEN} and no other connection is
     * currently bound.
     *
     * <p>Pod-affinity is no longer tracked here — it lives on the session's
     * project. Callers should ensure the project is claimed by this pod
     * before invoking {@code tryBind}.
     *
     * @return {@code true} if the caller now owns the lock, {@code false} if
     *         the session is closed, missing, or already held by someone else.
     */
    public boolean tryBind(String sessionId, String connectionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_STATUS).is(SessionStatus.OPEN)
                .and(F_BOUND_CONNECTION).isNull());
        Update update = new Update()
                .set(F_BOUND_CONNECTION, connectionId)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        boolean bound = result.getModifiedCount() == 1;
        if (bound) {
            log.debug("Bound session '{}' to connection '{}'", sessionId, connectionId);
        } else {
            log.debug("Bind rejected for session '{}' — no matching OPEN/unbound record", sessionId);
        }
        return bound;
    }

    /**
     * Releases the connection lock if this caller still owns it. Safe to call
     * on sessions that were already unbound — it just does nothing.
     */
    public void unbind(String sessionId, String connectionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_BOUND_CONNECTION).is(connectionId));
        Update update = new Update()
                .set(F_BOUND_CONNECTION, null)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.debug("Unbound session '{}' from connection '{}'", sessionId, connectionId);
        }
    }

    /**
     * Mass-release the {@code boundConnectionId} for every session whose
     * {@code projectId} is in {@code projectIds}. Used by the project
     * startup-reclaimer when a pod takes over its own projects on
     * restart — pre-restart connections are gone, the bookkeeping isn't.
     *
     * @return number of sessions that were released
     */
    public long unbindAllForProjects(java.util.Collection<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return 0;
        Query query = new Query(Criteria.where("projectId").in(projectIds)
                .and(F_BOUND_CONNECTION).ne(null));
        Update update = new Update().set(F_BOUND_CONNECTION, null);
        UpdateResult result = mongoTemplate.updateMulti(query, update, SessionDocument.class);
        long n = result.getModifiedCount();
        if (n > 0) {
            log.info("Reclaimed {} stale session binding(s) for {} project(s)",
                    n, projectIds.size());
        }
        return n;
    }

    /**
     * Bumps {@link SessionDocument#getLastActivityAt()} — but only if this
     * connection still owns the lock. Returns {@code false} if the lock has
     * been lost (session closed, taken over, unbound) — the caller should
     * then drop the connection.
     */
    public boolean heartbeat(String sessionId, String connectionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_BOUND_CONNECTION).is(connectionId));
        Update update = new Update().set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        return result.getModifiedCount() == 1;
    }

    /**
     * Atomically links a freshly-spawned chat-process to the session.
     * Succeeds only if the session currently has no
     * {@code chatProcessId} — protects against double-bootstrap on
     * a concurrent session-create + session-bootstrap race.
     *
     * @return {@code true} if the session was updated; {@code false}
     *         when the session is missing or already has a chat
     *         process linked
     */
    public boolean setChatProcessId(String sessionId, String chatProcessId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .andOperator(new Criteria().orOperator(
                        Criteria.where(F_CHAT_PROCESS_ID).isNull(),
                        Criteria.where(F_CHAT_PROCESS_ID).exists(false))));
        Update update = new Update().set(F_CHAT_PROCESS_ID, chatProcessId);
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        boolean ok = result.getModifiedCount() == 1;
        if (ok) {
            log.debug("Linked chatProcessId='{}' to session='{}'",
                    chatProcessId, sessionId);
        }
        return ok;
    }

    // --------------------------------------------------------- lifecycle end

    /**
     * Closes the session (marks {@link SessionStatus#CLOSED}, clears binding).
     * Idempotent.
     */
    public void close(String sessionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId));
        Update update = new Update()
                .set(F_STATUS, SessionStatus.CLOSED)
                .set(F_BOUND_CONNECTION, null)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.info("Closed session '{}'", sessionId);
        }
    }

    /** Physically drops the session record. For the idle-timeout cleanup path. */
    public void delete(String sessionId) {
        repository.findBySessionId(sessionId).ifPresent(repository::delete);
    }

    // ------------------------------------------------------------ utilities

    private static String newSessionId() {
        return SESSION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
