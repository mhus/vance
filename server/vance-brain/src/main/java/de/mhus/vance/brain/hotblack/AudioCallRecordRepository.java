package de.mhus.vance.brain.hotblack;

import de.mhus.vance.shared.hotblack.AudioCallRecord;
import java.time.Instant;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link AudioCallRecord}. Append-only;
 * retention / cleanup is an operational concern outside this layer.
 *
 * <p>Quota math reads through the {@code countByTenant…} variants —
 * keeps the {@link AudioCallTracker} free of raw query strings.
 */
interface AudioCallRecordRepository extends MongoRepository<AudioCallRecord, String> {

    long countByTenantIdAndModalityAndAtGreaterThanEqual(String tenantId, String modality, Instant since);

    long countByTenantIdAndAccountIdAndAtGreaterThanEqual(String tenantId, String accountId, Instant since);
}
