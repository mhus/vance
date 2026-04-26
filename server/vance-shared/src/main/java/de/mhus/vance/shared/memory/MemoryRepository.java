package de.mhus.vance.shared.memory;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link MemoryDocument}. Package-private —
 * callers go through {@link MemoryService}.
 */
interface MemoryRepository extends MongoRepository<MemoryDocument, String> {

    List<MemoryDocument> findByTenantIdAndThinkProcessId(
            String tenantId, String thinkProcessId, Sort sort);

    List<MemoryDocument> findByTenantIdAndThinkProcessIdAndKind(
            String tenantId, String thinkProcessId, MemoryKind kind, Sort sort);

    List<MemoryDocument> findByTenantIdAndThinkProcessIdAndKindAndSupersededAtIsNull(
            String tenantId, String thinkProcessId, MemoryKind kind, Sort sort);

    /** Active entries of one kind+title — used for scratchpad named-slot lookup. */
    List<MemoryDocument> findByTenantIdAndThinkProcessIdAndKindAndTitleAndSupersededAtIsNull(
            String tenantId, String thinkProcessId, MemoryKind kind, String title, Sort sort);

    List<MemoryDocument> findByTenantIdAndSessionIdAndKind(
            String tenantId, String sessionId, MemoryKind kind, Sort sort);

    List<MemoryDocument> findByTenantIdAndProjectIdAndKind(
            String tenantId, String projectId, MemoryKind kind, Sort sort);

    long deleteByTenantIdAndThinkProcessId(String tenantId, String thinkProcessId);
}
