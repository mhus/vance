package de.mhus.vance.shared.chat;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Chat-message lifecycle — append, read, cleanup. The single entry point to
 * chat-message data.
 *
 * <p>History is always returned in ascending {@code createdAt} order, the
 * natural order a caller would replay into an LLM context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    private final ChatMessageRepository repository;

    /**
     * Persists {@code message}. The {@code createdAt} timestamp is filled in
     * by the framework on insert; callers do not need to set it.
     */
    public ChatMessageDocument append(ChatMessageDocument message) {
        ChatMessageDocument saved = repository.save(message);
        log.debug("Chat message appended tenant='{}' session='{}' process='{}' role={} id='{}'",
                saved.getTenantId(), saved.getSessionId(), saved.getThinkProcessId(),
                saved.getRole(), saved.getId());
        return saved;
    }

    /**
     * Full chat history for a think-process, ordered by {@code createdAt}
     * ascending. Returns an empty list if there are no messages yet.
     */
    public List<ChatMessageDocument> history(
            String tenantId, String sessionId, String thinkProcessId) {
        return repository.findByTenantIdAndSessionIdAndThinkProcessId(
                tenantId, sessionId, thinkProcessId,
                Sort.by(Sort.Direction.ASC, "createdAt"));
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
