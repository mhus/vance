package de.mhus.vance.shared.chat;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ChatMessageDocument}. Package-private —
 * callers go through {@link ChatMessageService}.
 */
interface ChatMessageRepository extends MongoRepository<ChatMessageDocument, String> {

    List<ChatMessageDocument> findByTenantIdAndSessionIdAndThinkProcessId(
            String tenantId, String sessionId, String thinkProcessId, Sort sort);

    /** Active history — messages not yet rolled into a memory compaction. */
    List<ChatMessageDocument> findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
            String tenantId, String sessionId, String thinkProcessId, Sort sort);

    /**
     * Active history of the whole session — every process, not just the
     * chat-process. Backs the UI scrollback, which shows worker output as
     * {@code [processName · role]}-tagged notes.
     */
    List<ChatMessageDocument> findByTenantIdAndSessionIdAndArchivedInMemoryIdIsNull(
            String tenantId, String sessionId, Sort sort);

    long deleteByTenantIdAndSessionIdAndThinkProcessId(
            String tenantId, String sessionId, String thinkProcessId);

    long deleteByTenantIdAndSessionId(String tenantId, String sessionId);
}
