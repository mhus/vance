package de.mhus.vance.shared.session;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.session.SuspendPolicy;
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
    private static final String F_FIRST_USER_MESSAGE = "firstUserMessage";
    private static final String F_LAST_MESSAGE_PREVIEW = "lastMessagePreview";
    private static final String F_LAST_MESSAGE_ROLE = "lastMessageRole";
    private static final String F_LAST_MESSAGE_AT = "lastMessageAt";
    private static final String F_CLIENT_AGENT_DOC = "clientAgentDoc";
    private static final String F_CLIENT_AGENT_DOC_PATH = "clientAgentDocPath";

    /** Hard cap for the denormalised preview / topic strings. */
    public static final int PREVIEW_MAX_CHARS = 250;

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

    /** Admin-style cross-user listing — used by the insights inspector. */
    public List<SessionDocument> listForTenant(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /** Admin-style cross-user listing scoped to a project. */
    public List<SessionDocument> listForProject(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    // ------------------------------------------------------------- creation

    /**
     * Creates a new session in {@link SessionStatus#INIT}, scoped to
     * {@code projectId}. No connection is bound yet — call {@link #tryBind}
     * immediately after if the caller is about to hold the connection.
     *
     * <p>The session starts with {@link SessionLifecycleConfig#safeDefault()};
     * the bootstrap path replaces it via {@link #applyLifecycleConfig}
     * once the recipe has been resolved.
     */
    public SessionDocument create(
            String tenantId,
            String userId,
            String projectId,
            @Nullable String displayName,
            String profile,
            String clientVersion,
            @Nullable String clientName) {
        Instant now = Instant.now();
        SessionLifecycleConfig defaults = SessionLifecycleConfig.safeDefault();
        SessionDocument doc = SessionDocument.builder()
                .sessionId(newSessionId())
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .displayName(displayName)
                .profile(profile)
                .clientVersion(clientVersion)
                .clientName(clientName)
                .boundConnectionId(null)
                .status(SessionStatus.INIT)
                .onDisconnect(defaults.getOnDisconnect())
                .onIdle(defaults.getOnIdle())
                .onSuspend(defaults.getOnSuspend())
                .idleTimeoutMs(defaults.getIdleTimeoutMs())
                .suspendKeepDurationMs(defaults.getSuspendKeepDurationMs())
                .createdAt(now)
                .lastActivityAt(now)
                .build();
        SessionDocument saved = repository.save(doc);
        log.info("Created session sessionId='{}' tenant='{}' user='{}' project='{}'",
                saved.getSessionId(), saved.getTenantId(), saved.getUserId(), saved.getProjectId());
        return saved;
    }

    /**
     * Replaces the lifecycle config on a session — used by the
     * bootstrap path immediately after recipe resolution. Once
     * persisted, the policy fields are read by the lifecycle code
     * and not changed again for the lifetime of the session.
     */
    public void applyLifecycleConfig(String sessionId, SessionLifecycleConfig cfg) {
        Update update = new Update()
                .set("onDisconnect", cfg.getOnDisconnect())
                .set("onIdle", cfg.getOnIdle())
                .set("onSuspend", cfg.getOnSuspend())
                .set("idleTimeoutMs", cfg.getIdleTimeoutMs())
                .set("suspendKeepDurationMs", cfg.getSuspendKeepDurationMs());
        mongoTemplate.updateFirst(
                new Query(Criteria.where(F_SESSION_ID).is(sessionId)),
                update,
                SessionDocument.class);
    }

    /**
     * Marks a session's bootstrap as complete: status flips from
     * {@link SessionStatus#INIT} to {@link SessionStatus#IDLE}.
     * No-op for sessions that are already past INIT.
     */
    public void markBootstrapped(String sessionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_STATUS).is(SessionStatus.INIT));
        Update update = new Update().set(F_STATUS, SessionStatus.IDLE);
        mongoTemplate.updateFirst(query, update, SessionDocument.class);
    }

    // --------------------------------------------------------- atomic locks

    /**
     * Atomically claims the connection lock on {@code sessionId}. Succeeds only
     * if the session is in a bindable status (everything except
     * {@link SessionStatus#CLOSED}) and no other connection is currently bound.
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
                .and(F_STATUS).ne(SessionStatus.CLOSED)
                .and(F_BOUND_CONNECTION).isNull());
        Update update = new Update()
                .set(F_BOUND_CONNECTION, connectionId)
                .set(F_LAST_ACTIVITY, Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        boolean bound = result.getModifiedCount() == 1;
        if (bound) {
            log.debug("Bound session '{}' to connection '{}'", sessionId, connectionId);
        } else {
            log.debug("Bind rejected for session '{}' — no matching non-CLOSED/unbound record", sessionId);
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
     * Stores the client-uploaded agent doc on the session. Idempotent —
     * a second upload overwrites the first. Pass {@code null} content to
     * clear (the corresponding fields are unset).
     */
    public void setClientAgentDoc(
            String sessionId, @Nullable String path, @Nullable String content) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId));
        Update update = new Update();
        if (content == null) {
            update.unset(F_CLIENT_AGENT_DOC).unset(F_CLIENT_AGENT_DOC_PATH);
        } else {
            update.set(F_CLIENT_AGENT_DOC, content)
                    .set(F_CLIENT_AGENT_DOC_PATH, path);
        }
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.info("Stored client agent doc for session='{}' path='{}' chars={}",
                    sessionId, path, content == null ? 0 : content.length());
        }
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

    /**
     * Updates the denormalised chat preview ({@code lastMessagePreview} +
     * {@code lastMessageAt} + {@code lastMessageRole}) on the session doc;
     * additionally seeds {@code firstUserMessage} on the very first
     * {@code USER}-role message and never overwrites it later. Two
     * conditional updates so a later USER message can't replace the
     * stable "topic". Idempotent against re-runs with the same content.
     *
     * <p>Called from {@link de.mhus.vance.shared.chat.ChatMessageService#append}
     * — keeps the chat hot-path adding at most two atomic Mongo ops.
     */
    public void touchChatPreview(
            String sessionId,
            @Nullable String role,
            @Nullable String content,
            @Nullable Instant at) {
        if (sessionId == null || sessionId.isBlank()) return;
        String preview = truncate(content);
        Instant when = at == null ? Instant.now() : at;

        Update lastUpdate = new Update()
                .set(F_LAST_MESSAGE_PREVIEW, preview)
                .set(F_LAST_MESSAGE_ROLE, role)
                .set(F_LAST_MESSAGE_AT, when);
        mongoTemplate.updateFirst(
                new Query(Criteria.where(F_SESSION_ID).is(sessionId)),
                lastUpdate,
                SessionDocument.class);

        if ("USER".equalsIgnoreCase(role) && preview != null) {
            // Set firstUserMessage only when the field is currently null /
            // missing — captures the session's "topic" once.
            Query firstQuery = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                    .andOperator(new Criteria().orOperator(
                            Criteria.where(F_FIRST_USER_MESSAGE).isNull(),
                            Criteria.where(F_FIRST_USER_MESSAGE).exists(false))));
            Update firstUpdate = new Update().set(F_FIRST_USER_MESSAGE, preview);
            mongoTemplate.updateFirst(firstQuery, firstUpdate, SessionDocument.class);
        }
    }

    private static @Nullable String truncate(@Nullable String content) {
        if (content == null) return null;
        // Collapse whitespace so multi-line messages stay readable in a
        // one-line preview slot.
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return null;
        if (flat.length() <= PREVIEW_MAX_CHARS) return flat;
        return flat.substring(0, PREVIEW_MAX_CHARS - 1) + "…";
    }

    // --------------------------------------------------------- lifecycle end

    /**
     * Closes the session (marks {@link SessionStatus#CLOSED}, clears
     * binding, clears suspend-runtime fields). Idempotent.
     *
     * <p>Used by the close-cascade in {@code SessionLifecycleService}
     * <em>after</em> all of the session's processes have been stopped.
     */
    public void close(String sessionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId));
        Update update = new Update()
                .set(F_STATUS, SessionStatus.CLOSED)
                .set(F_BOUND_CONNECTION, null)
                .set(F_LAST_ACTIVITY, Instant.now())
                .unset("suspendedAt")
                .unset("suspendCause")
                .unset("deleteAt");
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.info("Closed session '{}'", sessionId);
        }
    }

    /**
     * Marks the session SUSPENDED, stamping {@code suspendedAt},
     * {@code suspendCause}, {@code deleteAt}. {@code deleteAt}
     * computation respects {@code FORCED} as the override case
     * (use {@code forcedFloorMs} regardless of {@code onSuspend}).
     *
     * <p>Idempotent — a second suspend on an already-suspended
     * session keeps the original timestamps. (We do not "refresh"
     * deleteAt on repeat suspends.)
     */
    public void suspend(String sessionId, SuspendCause cause, long forcedFloorMs) {
        SessionDocument session = repository.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        if (session.getStatus() == SessionStatus.SUSPENDED
                || session.getStatus() == SessionStatus.CLOSED) {
            return;
        }
        Instant now = Instant.now();
        Instant deleteAt;
        if (cause == SuspendCause.FORCED) {
            deleteAt = now.plusMillis(forcedFloorMs);
        } else if (session.getOnSuspend() == SuspendPolicy.CLOSE) {
            deleteAt = now;
        } else {
            deleteAt = now.plusMillis(session.getSuspendKeepDurationMs());
        }
        Update update = new Update()
                .set(F_STATUS, SessionStatus.SUSPENDED)
                .set("suspendedAt", now)
                .set("suspendCause", cause)
                .set("deleteAt", deleteAt)
                .set(F_LAST_ACTIVITY, now);
        mongoTemplate.updateFirst(
                new Query(Criteria.where(F_SESSION_ID).is(sessionId)),
                update, SessionDocument.class);
        log.info("Suspended session '{}' cause={} deleteAt={}",
                sessionId, cause, deleteAt);
    }

    /**
     * Resume a SUSPENDED session: status → IDLE, clear suspend-runtime fields.
     * Caller is responsible for resuming the engines.
     */
    public void resume(String sessionId) {
        Query query = new Query(Criteria.where(F_SESSION_ID).is(sessionId)
                .and(F_STATUS).is(SessionStatus.SUSPENDED));
        Update update = new Update()
                .set(F_STATUS, SessionStatus.IDLE)
                .set(F_LAST_ACTIVITY, Instant.now())
                .unset("suspendedAt")
                .unset("suspendCause")
                .unset("deleteAt");
        UpdateResult result = mongoTemplate.updateFirst(query, update, SessionDocument.class);
        if (result.getModifiedCount() == 1) {
            log.info("Resumed session '{}'", sessionId);
        }
    }

    /**
     * Returns sessions whose {@code deleteAt} has passed and that
     * are still in {@link SessionStatus#SUSPENDED}. The caller (sweep
     * job) is responsible for the close-cascade. Convenience query —
     * not part of the lifecycle code path.
     */
    public List<SessionDocument> findOverdueSuspended(Instant cutoff) {
        Query query = new Query(Criteria.where(F_STATUS).is(SessionStatus.SUSPENDED)
                .and("deleteAt").lte(cutoff));
        return mongoTemplate.find(query, SessionDocument.class);
    }

    /**
     * Returns SUSPENDED sessions whose {@code suspendCause} is
     * {@link SuspendCause#FORCED} and which this pod owns (via project).
     * Used by the auto-restart job on Brain boot.
     */
    public List<SessionDocument> findForcedSuspendedByProject(java.util.Collection<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return List.of();
        Query query = new Query(Criteria.where(F_STATUS).is(SessionStatus.SUSPENDED)
                .and("suspendCause").is(SuspendCause.FORCED)
                .and("projectId").in(projectIds));
        return mongoTemplate.find(query, SessionDocument.class);
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
