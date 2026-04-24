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

    long deleteByTenantIdAndSessionIdAndThinkProcessId(
            String tenantId, String sessionId, String thinkProcessId);

    long deleteByTenantIdAndSessionId(String tenantId, String sessionId);
}
