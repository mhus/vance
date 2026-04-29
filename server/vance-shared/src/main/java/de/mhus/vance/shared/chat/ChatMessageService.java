package de.mhus.vance.shared.chat;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.session.SessionService;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Chat-message lifecycle — append, read, cleanup. The single entry point to
 * chat-message data.
 *
 * <p>{@link #history} returns every message in chronological order, archive
 * markers and all — meant for audit/debug. The replay path used by
 * think-engines goes through {@link #activeHistory}, which filters out
 * messages already rolled into a memory compaction so the LLM sees the
 * summary instead of the originals. The originals stay in Mongo, readable
 * via {@link #history}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    /**
     * Primary key {@code createdAt} for chronological order, {@code id}
     * (the Mongo {@code ObjectId}) as tiebreaker — and as the only
     * reliable order for older documents inserted before Mongo
     * auditing was enabled. Their {@code createdAt} is {@code null}
     * so the primary key ranks them all together; the {@code ObjectId}
     * encodes the insert timestamp and breaks the tie monotonically.
     */
    private static final Sort BY_CREATED =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final ChatMessageRepository repository;
    private final MongoTemplate mongoTemplate;
    private final SessionService sessionService;

    /**
     * Persists {@code message}. The {@code createdAt} timestamp is filled in
     * by the framework on insert; callers do not need to set it.
     *
     * <p>After save, denormalises a short preview of the message onto
     * the owning {@code SessionDocument} so the inspector / session
     * picker can show "topic" + "what happened last" without reading
     * the full chat. See
     * {@link SessionService#touchChatPreview(String, String, String, java.time.Instant)}.
     */
    public ChatMessageDocument append(ChatMessageDocument message) {
        ChatMessageDocument saved = repository.save(message);
        log.debug("Chat message appended tenant='{}' session='{}' process='{}' role={} id='{}'",
                saved.getTenantId(), saved.getSessionId(), saved.getThinkProcessId(),
                saved.getRole(), saved.getId());
        sessionService.touchChatPreview(
                saved.getSessionId(),
                saved.getRole() == null ? null : saved.getRole().name(),
                saved.getContent(),
                saved.getCreatedAt());
        return saved;
    }

    /**
     * Full chat history for a think-process, ordered by {@code createdAt}
     * ascending — including messages that have been archived into a
     * compaction memory. Use {@link #activeHistory} for the LLM-replay path.
     */
    public List<ChatMessageDocument> history(
            String tenantId, String sessionId, String thinkProcessId) {
        return repository.findByTenantIdAndSessionIdAndThinkProcessId(
                tenantId, sessionId, thinkProcessId, BY_CREATED);
    }

    /**
     * Active chat history — the messages that have <em>not</em> been
     * archived into a compaction memory. This is what an engine should
     * replay into the LLM context.
     */
    public List<ChatMessageDocument> activeHistory(
            String tenantId, String sessionId, String thinkProcessId) {
        return repository.findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
                tenantId, sessionId, thinkProcessId, BY_CREATED);
    }

    /**
     * Atomically marks a set of chat messages as archived in
     * {@code memoryId}. Idempotent — re-running with the same id is a
     * no-op for already-archived rows. Returns the number of rows
     * actually flipped.
     */
    public long markArchived(Collection<String> messageIds, String memoryId) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        Query query = new Query(Criteria.where("_id").in(messageIds)
                .and("archivedInMemoryId").isNull());
        Update update = new Update().set("archivedInMemoryId", memoryId);
        UpdateResult result = mongoTemplate.updateMulti(query, update, ChatMessageDocument.class);
        long n = result.getModifiedCount();
        if (n > 0) {
            log.debug("Archived {} chat message(s) into memory '{}'", n, memoryId);
        }
        return n;
    }

    /** Drops all messages of a think-process (process deletion). */
    public long deleteByProcess(String tenantId, String sessionId, String thinkProcessId) {
        long n = repository.deleteByTenantIdAndSessionIdAndThinkProcessId(
                tenantId, sessionId, thinkProcessId);
        if (n > 0) {
            log.info("Deleted {} chat messages for process tenant='{}' session='{}' process='{}'",
                    n, tenantId, sessionId, thinkProcessId);
        }
        return n;
    }

    /** Drops all messages of a session (session close / cleanup). */
    public long deleteBySession(String tenantId, String sessionId) {
        long n = repository.deleteByTenantIdAndSessionId(tenantId, sessionId);
        if (n > 0) {
            log.info("Deleted {} chat messages for session tenant='{}' session='{}'",
                    n, tenantId, sessionId);
        }
        return n;
    }
}
