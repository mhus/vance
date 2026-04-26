package de.mhus.vance.shared.thinkprocess;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Lifecycle and lookup for {@link ThinkProcessDocument}. The one entry
 * point to think-process data.
 *
 * <p>Status transitions run through {@link #updateStatus} so callers don't
 * accidentally overwrite other fields on a read-modify-write cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThinkProcessService {

    private final ThinkProcessRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // ────────────────── Create ──────────────────

    /**
     * Creates a new think-process inside {@code sessionId}. Throws
     * {@link ThinkProcessAlreadyExistsException} if one with the same
     * {@code name} already exists in that session.
     */
    public ThinkProcessDocument create(
            String tenantId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal) {
        return create(tenantId, sessionId, name, thinkEngine,
                thinkEngineVersion, title, goal, /*parentProcessId*/ null);
    }

    /**
     * Same as {@link #create(String, String, String, String, String, String, String)},
     * but also records the orchestrator that spawned this process —
     * see {@link ThinkProcessDocument#getParentProcessId()}.
     */
    public ThinkProcessDocument create(
            String tenantId,
            String sessionId,
            String name,
            String thinkEngine,
            @Nullable String thinkEngineVersion,
            @Nullable String title,
            @Nullable String goal,
            @Nullable String parentProcessId) {
        if (repository.existsByTenantIdAndSessionIdAndName(tenantId, sessionId, name)) {
            throw new ThinkProcessAlreadyExistsException(
                    "Think-process '" + name + "' already exists in session '"
                            + sessionId + "' (tenant='" + tenantId + "')");
        }
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .tenantId(tenantId)
                .sessionId(sessionId)
                .name(name)
                .title(title)
                .thinkEngine(thinkEngine)
                .thinkEngineVersion(thinkEngineVersion)
                .goal(goal)
                .parentProcessId(parentProcessId)
                .status(ThinkProcessStatus.READY)
                .build();
        ThinkProcessDocument saved = repository.save(doc);
        log.info("Created think-process tenant='{}' session='{}' name='{}' engine='{}' id='{}' parent='{}'",
                tenantId, sessionId, name, thinkEngine, saved.getId(), parentProcessId);
        return saved;
    }

    // ────────────────── Read ──────────────────

    public Optional<ThinkProcessDocument> findById(String id) {
        return repository.findById(id);
    }

    public Optional<ThinkProcessDocument> findByName(
            String tenantId, String sessionId, String name) {
        return repository.findByTenantIdAndSessionIdAndName(tenantId, sessionId, name);
    }

    public List<ThinkProcessDocument> findBySession(String tenantId, String sessionId) {
        return repository.findByTenantIdAndSessionId(tenantId, sessionId);
    }

    public List<ThinkProcessDocument> findBySessionAndStatus(
            String tenantId, String sessionId, ThinkProcessStatus status) {
        return repository.findByTenantIdAndSessionIdAndStatus(tenantId, sessionId, status);
    }

    // ────────────────── Mutations ──────────────────

    /**
     * Atomically sets {@code status} on the process with the given Mongo id.
     * Returns {@code true} if the row exists, {@code false} if the id is
     * unknown.
     *
     * <p>Publishes a {@link ThinkProcessStatusChangedEvent} after a
     * successful update so listeners (e.g. parent-notification) can
     * react. The event is published <em>even when the new status
     * equals the prior</em> — listeners that care about transitions
     * filter on {@code priorStatus != newStatus} themselves; those
     * that just want a heartbeat can ignore the predicate.
     */
    public boolean updateStatus(String id, ThinkProcessStatus status) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("status", status);
        ThinkProcessDocument prior = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(false),
                ThinkProcessDocument.class);
        if (prior == null) {
            return false;
        }
        log.debug("Think-process status updated id='{}' {} -> {}",
                id, prior.getStatus(), status);
        eventPublisher.publishEvent(new ThinkProcessStatusChangedEvent(
                id,
                prior.getTenantId(),
                prior.getSessionId(),
                prior.getParentProcessId(),
                prior.getStatus(),
                status));
        return true;
    }

    // ────────────────── Pending Queue ──────────────────

    /**
     * Atomically appends one message to the process's pending inbox.
     * Returns {@code true} if the row exists and was updated.
     *
     * <p>Write-through: the append is independent of any in-flight
     * lane-turn — the message is durable the moment this returns.
     */
    public boolean appendPending(String processId, PendingMessageDocument message) {
        Query query = new Query(Criteria.where("_id").is(processId));
        Update update = new Update().push("pendingMessages", message);
        UpdateResult result = mongoTemplate.updateFirst(
                query, update, ThinkProcessDocument.class);
        boolean ok = result.getModifiedCount() > 0;
        if (ok) {
            log.debug("Pending append id='{}' type={} ", processId, message.getType());
        } else {
            log.warn("Pending append failed — process not found id='{}'", processId);
        }
        return ok;
    }

    /**
     * Atomically reads and clears the pending inbox of {@code processId}.
     *
     * <p>Returns the messages that were queued, in insertion order.
     * Returns an empty list if the process is unknown or had no
     * pending work — never {@code null}. New messages that arrive
     * after this call land in the freshly-emptied queue and feed the
     * next lane-turn (Auto-Wakeup).
     */
    public List<PendingMessageDocument> drainPending(String processId) {
        Query query = new Query(Criteria.where("_id").is(processId));
        Update update = new Update().set("pendingMessages", new ArrayList<>());
        ThinkProcessDocument prior = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(false),
                ThinkProcessDocument.class);
        if (prior == null) {
            return Collections.emptyList();
        }
        List<PendingMessageDocument> drained = prior.getPendingMessages();
        if (drained == null || drained.isEmpty()) {
            return Collections.emptyList();
        }
        log.debug("Pending drain id='{}' count={}", processId, drained.size());
        return drained;
    }

    /**
     * Cheap inspection of pending-queue length without consuming it.
     * Returns {@code 0} if the process is unknown.
     */
    public int pendingSize(String processId) {
        return repository.findById(processId)
                .map(d -> d.getPendingMessages() == null ? 0 : d.getPendingMessages().size())
                .orElse(0);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    /** Drops all processes of a session (session-close cleanup). */
    public long deleteBySession(String tenantId, String sessionId) {
        long n = repository.deleteByTenantIdAndSessionId(tenantId, sessionId);
        if (n > 0) {
            log.info("Deleted {} think-processes for session tenant='{}' session='{}'",
                    n, tenantId, sessionId);
        }
        return n;
    }

    public static class ThinkProcessAlreadyExistsException extends RuntimeException {
        public ThinkProcessAlreadyExistsException(String message) {
            super(message);
        }
    }
}
