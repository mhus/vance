package de.mhus.vance.brain.eddie.activity;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Spring Data repository for the Vance Activity-Log.
 */
public interface EddieActivityRepository extends MongoRepository<EddieActivityEntry, String> {

    /**
     * Recap query: entries for {@code userId} since {@code since},
     * excluding the calling Vance process so the result is "what
     * everyone else has been up to". Sorted newest-first by the
     * {@code user_ts_idx} compound index.
     */
    @Query(value = "{ "
            + "'tenantId': ?0, "
            + "'userId': ?1, "
            + "'vanceProcessId': { $ne: ?2 }, "
            + "'ts': { $gte: ?3 } }",
            sort = "{ 'ts': -1 }")
    List<EddieActivityEntry> findPeerActivity(
            String tenantId,
            String userId,
            String excludingVanceProcessId,
            Instant since,
            Pageable pageable);
}
