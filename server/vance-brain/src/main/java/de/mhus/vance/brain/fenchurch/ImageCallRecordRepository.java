package de.mhus.vance.brain.fenchurch;

import java.time.Instant;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ImageCallRecord}. Append-only;
 * retention / cleanup is an operational concern outside this layer.
 *
 * <p>Quota math reads through the {@code countByTenant…} variants
 * — keeps the {@link ImageCallTracker} free of raw query strings.
 */
interface ImageCallRecordRepository
        extends MongoRepository<ImageCallRecord, String> {

    long countByTenantIdAndAtGreaterThanEqual(String tenantId, Instant since);

    long countByTenantIdAndAccountIdAndAtGreaterThanEqual(
            String tenantId, String accountId, Instant since);

    long countByTenantIdAndProjectIdAndAtGreaterThanEqual(
            String tenantId, String projectId, Instant since);
}
