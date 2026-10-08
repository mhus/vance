package de.mhus.vance.brain.trillian;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Cross-pod arbiter for the Trillian loop. The loop homes are podless hubs
 * (D2), so no pod owns a loop, and two things need a referee:
 *
 * <ul>
 *   <li><b>Wake slots</b> ({@link #claim}): the heartbeat runs on every pod;
 *       one {@link TrillianWakeupClaimDocument} keyed by
 *       {@code (tenant, loop, slot)} makes the insert the arbiter. First pod
 *       wins and fires, the others skip.</li>
 *   <li><b>Leases</b> ({@link #acquireLease}/{@link #releaseLease}): a turn of
 *       the loop, and the bootstrap of a pair, must not run on two pods at
 *       once — the lane serialises per pod only. A lease is the same document
 *       with a release; a holder that died is taken over once its lease is
 *       older than the TTL the caller names.</li>
 * </ul>
 *
 * <p>Owner of {@code trillian_wakeup_claims}; nothing else writes there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianWakeupClaimService {

    private final MongoTemplate mongoTemplate;

    /**
     * Try to claim one wake slot of this loop. Returns {@code true} when this
     * pod won the slot and should wake the loop. {@code slot} distinguishes
     * appointments: the ladder's due millis, a schedule's due millis.
     */
    public boolean claim(String tenantId, String loopProcessId, String slot) {
        String id = tenantId + '/' + loopProcessId + '/' + slot;
        try {
            mongoTemplate.insert(TrillianWakeupClaimDocument.builder()
                    .id(id)
                    .claimedAt(Instant.now())
                    .build());
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Trillian wakeup slot '{}' already claimed by another pod — skipping", id);
            return false;
        }
    }

    /**
     * Takes the lease {@code key}. Succeeds when nobody holds it, or when the
     * holder's lease is older than {@code ttl} — a pod that died mid-turn
     * must not block the loop for the hour the TTL index needs.
     */
    public boolean acquireLease(String key, Duration ttl) {
        String id = "lease/" + key;
        Instant now = Instant.now();
        try {
            mongoTemplate.insert(
                    TrillianWakeupClaimDocument.builder().id(id).claimedAt(now).build());
            return true;
        } catch (DuplicateKeyException e) {
            Query stale =
                    new Query(Criteria.where("_id").is(id).and("claimedAt").lt(now.minus(ttl)));
            boolean taken = mongoTemplate
                            .updateFirst(stale, new Update().set("claimedAt", now), TrillianWakeupClaimDocument.class)
                            .getModifiedCount()
                    == 1;
            if (taken) {
                log.info("Trillian lease '{}' was stale — taken over", key);
            }
            return taken;
        }
    }

    /** Gives the lease back. A lease that is already gone is fine. */
    public void releaseLease(String key) {
        mongoTemplate.remove(new Query(Criteria.where("_id").is("lease/" + key)), TrillianWakeupClaimDocument.class);
    }
}
