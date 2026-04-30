package de.mhus.vance.shared.llmtrace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data MongoDB repository for {@link LlmTraceDocument}.
 *
 * <p>Reads always include {@code tenantId} so tenant scoping is enforced
 * by query, not by access-filter alone. Writes go via
 * {@link LlmTraceService} (datenhoheit) — never bypass the service for
 * writes.
 */
public interface LlmTraceRepository extends MongoRepository<LlmTraceDocument, String> {

    /**
     * Pageable history for one process — sorted by {@code createdAt}
     * via the supplied {@link Pageable}. Index
     * {@code tenant_process_createdAt_idx} backs this query.
     */
    Page<LlmTraceDocument> findByTenantIdAndProcessId(
            String tenantId, String processId, Pageable pageable);

    /**
     * All entries for one round-trip. Useful for the Insights UI to
     * render a single round-trip detail view.
     */
    java.util.List<LlmTraceDocument> findByTenantIdAndProcessIdAndTurnIdOrderBySequenceAsc(
            String tenantId, String processId, String turnId);

    /** Best-effort cleanup hook — TTL handles the bulk; this is for explicit deletes. */
    long deleteByTenantIdAndProcessId(String tenantId, String processId);
}
