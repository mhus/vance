package de.mhus.vance.brain.hotblack;

import de.mhus.vance.shared.hotblack.AudioCallRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link AudioCallRecord}. Append-only;
 * retention / cleanup is an operational concern outside this layer.
 * Rows are finalized in place (reserve row → real outcome), which is
 * how one call stays one row.
 *
 * <p>Quota math reads through the {@code countByTenant…} variants —
 * keeps the {@link AudioCallTracker} free of raw query strings.
 */
interface AudioCallRecordRepository extends MongoRepository<AudioCallRecord, String> {

    long countByTenantIdAndModalityAndAtGreaterThanEqual(String tenantId, String modality, Instant since);

    long countByTenantIdAndAccountIdAndAtGreaterThanEqual(String tenantId, String accountId, Instant since);

    /**
     * Rows still carrying the given outcome (the reserve marker) whose call
     * started before {@code before} — the input of the abandoned-reserve sweep.
     */
    List<AudioCallRecord> findByOutcomeAndAtBefore(String outcome, Instant before);
}
