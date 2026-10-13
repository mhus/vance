package de.mhus.vance.brain.trillian;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Cluster-wide leases for the Trillian loop: a turn of the loop, and the
 * bootstrap of a pair, must not run on two pods at once — the lane serialises
 * per pod only. The loop's hub has a home pod that wakes it, but a process
 * can still be turned elsewhere: a message routed while the hub is between
 * holders is delivered on the receiving pod, and the bootstrap starts the
 * loop on the control session's pod. A lease is one
 * {@link TrillianWakeupClaimDocument} with a release; a holder that died is
 * taken over once its lease is older than the TTL the caller names — and the
 * release is owner-checked ({@link #releaseLease(String, String)} carries the
 * acquirer's token), so a holder that outlives its TTL cannot free its
 * successor's lease.
 *
 * <p>Owner of {@code trillian_wakeup_claims}; nothing else writes there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianWakeupClaimService {

    private final MongoTemplate mongoTemplate;

    /**
     * Takes the lease {@code key}. Succeeds when nobody holds it, or when the
     * holder's lease is older than {@code ttl} — a pod that died mid-turn
     * must not block the loop for the hour the TTL index needs. Returns the
     * <b>holder token</b> the acquirer must hand back on
     * {@link #releaseLease(String, String)}; {@code null} when the lease is
     * held fresh. The token makes the release owner-checked: a holder that
     * outlives its own TTL and wakes up after a takeover must not free its
     * successor's lease.
     */
    public @org.jspecify.annotations.Nullable String acquireLease(String key, Duration ttl) {
        String id = "lease/" + key;
        Instant now = Instant.now();
        String holder = UUID.randomUUID().toString();
        try {
            mongoTemplate.insert(TrillianWakeupClaimDocument.builder()
                    .id(id)
                    .claimedAt(now)
                    .holder(holder)
                    .build());
            return holder;
        } catch (DuplicateKeyException e) {
            Query stale =
                    new Query(Criteria.where("_id").is(id).and("claimedAt").lt(now.minus(ttl)));
            boolean taken = mongoTemplate
                            .updateFirst(
                                    stale,
                                    new Update().set("claimedAt", now).set("holder", holder),
                                    TrillianWakeupClaimDocument.class)
                            .getModifiedCount()
                    == 1;
            if (taken) {
                log.info("Trillian lease '{}' was stale — taken over", key);
            }
            return taken ? holder : null;
        }
    }

    /**
     * Gives the lease back — but only while {@code holder} still owns it: a
     * lease taken over after its TTL belongs to the taker, and the late
     * original holder's release must not remove it. A release that finds a
     * different holder (or none) is a no-op with a log line — the same
     * takeover the acquire path already logged.
     */
    public void releaseLease(String key, String holder) {
        Query owned =
                new Query(Criteria.where("_id").is("lease/" + key).and("holder").is(holder));
        if (mongoTemplate.remove(owned, TrillianWakeupClaimDocument.class).getDeletedCount() == 0) {
            log.info("Trillian lease '{}' no longer held by this acquirer — release ignored", key);
        }
    }
}
