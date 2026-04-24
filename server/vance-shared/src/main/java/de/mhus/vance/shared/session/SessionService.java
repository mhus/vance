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
    private static final String F_BOUND_POD_IP = "boundPodIp";
    private static final String F_LAST_ACTIVITY = "lastActivityAt";

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
                .boundPodIp(null)
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
     * @return {@code true} if the caller now owns the lock, {@code false} if
     *         the session is closed, missing, or already held by someone else.
     */
    public boolean tryBind(String sessionId, String connectionId, String podIp) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_STATUS).is(SessionStatus.OPEN)
                .and(F_BOUND_CONNECTION).isNull());
        Update update = new Update()
                .set(F_BOUND_CONNECTION, connectionId)
                .set(F_BOUND_POD_IP, podIp)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        boolean bound = result.getModifiedCount() == 1;
        if (bound) {
            log.debug("Bound session '{}' to connection '{}' on pod '{}'",
                    sessionId, connectionId, podIp);
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
                .set(F_BOUND_POD_IP, null)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.debug("Unbound session '{}' from connection '{}'", sessionId, connectionId);
        }
    }

    /**
     * Mass-release every session currently bound to {@code podIp}. Intended
     * for a pod's own startup cleanup: if a previous instance of this pod
     * crashed, its leftover bindings would block Auto-Resume; releasing
     * them here makes the next connect pick them up cleanly.
     *
     * @return number of sessions that were released
     */
    public long unbindAllByPod(String podIp) {
        Query query = new Query(Criteria.where(F_BOUND_POD_IP).is(podIp));
        Update update = new Update()
                .set(F_BOUND_CONNECTION, null)
                .set(F_BOUND_POD_IP, null);
        UpdateResult result = mongoTemplate.updateMulti(query, update, SessionDocument.class);
        long n = result.getModifiedCount();
        if (n > 0) {
            log.info("Unbound {} stale session(s) previously bound to pod '{}'", n, podIp);
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
                .set(F_BOUND_POD_IP, null)
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
