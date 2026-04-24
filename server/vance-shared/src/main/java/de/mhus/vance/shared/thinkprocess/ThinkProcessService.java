package de.mhus.vance.shared.thinkprocess;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
                .status(ThinkProcessStatus.READY)
                .build();
        ThinkProcessDocument saved = repository.save(doc);
        log.info("Created think-process tenant='{}' session='{}' name='{}' engine='{}' id='{}'",
                tenantId, sessionId, name, thinkEngine, saved.getId());
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
     * Returns {@code true} if the row was updated, {@code false} if the id
     * is unknown.
     */
    public boolean updateStatus(String id, ThinkProcessStatus status) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("status", status);
        UpdateResult result = mongoTemplate.updateFirst(query, update, ThinkProcessDocument.class);
        boolean ok = result.getModifiedCount() > 0;
        if (ok) {
            log.debug("Think-process status updated id='{}' -> {}", id, status);
        }
        return ok;
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
