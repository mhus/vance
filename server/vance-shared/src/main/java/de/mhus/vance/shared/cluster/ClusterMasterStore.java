package de.mhus.vance.shared.cluster;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Atomic CAS operations on the {@code cluster_master} lease document.
 * Persistence-only — election policy (when to renew, when to steal) lives
 * in the brain-side service.
 *
 * <p>All three mutating operations are single MongoDB {@code findAndModify}
 * calls and return the new document state on a win, {@link Optional#empty()}
 * on a lost race.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterMasterStore {

    private static final String F_ID = "_id";
    private static final String F_POD_ID = "currentPodId";
    private static final String F_NODE = "currentNodeName";
    private static final String F_ENDPOINT = "currentEndpoint";
    private static final String F_LEASE_UNTIL = "leaseUntil";

    private final MongoTemplate mongoTemplate;
    private final ClusterMasterRepository repository;

    /**
     * Reads the lease row for {@code clusterId}. Returns empty when nobody
     * has ever written it (initial state before any pod registers).
     */
    public Optional<ClusterMasterDocument> find(String clusterId) {
        return repository.findById(clusterId);
    }

    /**
     * Renew the lease for {@code podId}. Only succeeds when the current
     * row still names {@code podId} as master; returns empty otherwise
     * (someone else stole the lease in between).
     */
    public Optional<ClusterMasterDocument> renew(
            String clusterId, String podId, Instant newLeaseUntil) {
        Query query = new Query(Criteria.where(F_ID).is(clusterId)
                .and(F_POD_ID).is(podId));
        Update update = new Update().set(F_LEASE_UNTIL, newLeaseUntil);
        ClusterMasterDocument result = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ClusterMasterDocument.class);
        return Optional.ofNullable(result);
    }

    /**
     * Try to steal an unclaimed or expired lease. CAS-predicate: the row's
     * {@code currentPodId} matches the observed {@code expectedPodId}
     * (may be {@code null}) AND {@code leaseUntil <= now}. Returns the
     * new document on a win, empty when another pod stole first.
     *
     * <p>If no row exists yet, upserts a fresh one with the new master.
     */
    public Optional<ClusterMasterDocument> tryAcquire(
            String clusterId,
            @org.jspecify.annotations.Nullable String expectedPodId,
            String selfPodId,
            String selfNodeName,
            String selfEndpoint,
            Instant now,
            Instant newLeaseUntil) {
        // Upsert path for the first-ever election: no document exists yet.
        if (!repository.existsById(clusterId)) {
            ClusterMasterDocument fresh = ClusterMasterDocument.builder()
                    .clusterId(clusterId)
                    .currentPodId(selfPodId)
                    .currentNodeName(selfNodeName)
                    .currentEndpoint(selfEndpoint)
                    .leaseUntil(newLeaseUntil)
                    .build();
            try {
                return Optional.of(repository.insert(fresh));
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // Another pod inserted concurrently — fall through to CAS path.
                log.debug("ClusterMasterStore: concurrent insert for cluster '{}' — falling through", clusterId);
            }
        }
        Criteria base = Criteria.where(F_ID).is(clusterId);
        Criteria casPodId = (expectedPodId == null)
                ? new Criteria().orOperator(
                        Criteria.where(F_POD_ID).is(null),
                        Criteria.where(F_POD_ID).exists(false))
                : Criteria.where(F_POD_ID).is(expectedPodId);
        Criteria expired = new Criteria().orOperator(
                Criteria.where(F_LEASE_UNTIL).is(null),
                Criteria.where(F_LEASE_UNTIL).lte(now));
        Query query = new Query(new Criteria().andOperator(base, casPodId, expired));
        Update update = new Update()
                .set(F_POD_ID, selfPodId)
                .set(F_NODE, selfNodeName)
                .set(F_ENDPOINT, selfEndpoint)
                .set(F_LEASE_UNTIL, newLeaseUntil);
        ClusterMasterDocument result = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ClusterMasterDocument.class);
        return Optional.ofNullable(result);
    }

    /**
     * Best-effort release on shutdown. Only clears the slot when the
     * current row still names {@code podId} — never clears another pod's
     * claim. Returns {@code true} if the slot was cleared.
     */
    public boolean release(String clusterId, String podId) {
        Query query = new Query(Criteria.where(F_ID).is(clusterId)
                .and(F_POD_ID).is(podId));
        Update update = new Update()
                .set(F_POD_ID, null)
                .set(F_NODE, null)
                .set(F_ENDPOINT, null)
                .set(F_LEASE_UNTIL, null);
        ClusterMasterDocument result = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ClusterMasterDocument.class);
        return result != null;
    }
}
