package de.mhus.vance.shared.llmusage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data MongoDB repository for {@link LlmUsageDocument}.
 *
 * <p>Writes go via {@code LlmUsageService} (datenhoheit). Reads should
 * be tenant-scoped — repository methods enforce that with the
 * {@code tenantId} prefix on every finder. Aggregation queries (sum
 * per day / project / model) live in {@code LlmUsageReportService} and
 * use {@link org.springframework.data.mongodb.core.MongoTemplate}
 * directly with {@code $group} pipelines — Spring Data repository
 * methods are not expressive enough for time-bucketed aggregation.
 */
public interface LlmUsageRepository extends MongoRepository<LlmUsageDocument, String> {

    /**
     * Time-windowed list for one tenant. Used by report endpoints
     * that need raw rows (e.g. CSV export). For aggregated views go
     * through {@code LlmUsageReportService}.
     */
    List<LlmUsageDocument> findByTenantIdAndCreatedAtBetween(
            String tenantId, Instant from, Instant to);

    /** Best-effort cleanup hook — explicit per-process delete. */
    long deleteByTenantIdAndProcessId(String tenantId, String processId);
}
