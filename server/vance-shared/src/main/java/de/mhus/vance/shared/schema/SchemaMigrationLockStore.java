package de.mhus.vance.shared.schema;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Atomic CAS operations on the {@link SchemaMigrationLockDocument}.
 * Persistence-only — when to wait and for how long is
 * {@link SchemaMigrationService}'s policy.
 *
 * <p>Same shape as {@code ClusterMasterStore}: every mutation is a single
 * {@code findAndModify}, returning {@code true} on a win and {@code false} on a
 * lost race.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaMigrationLockStore {

    /** The one lock row — migrations are per-database. */
    public static final String LOCK_ID = "global";

    private static final String F_ID = "_id";
    private static final String F_OWNER = "owner";
    private static final String F_ACQUIRED_AT = "acquiredAt";
    private static final String F_EXPIRES_AT = "expiresAt";

    private final MongoTemplate mongoTemplate;

    /**
     * Takes the lock for {@code owner} when it is free or expired. First boot
     * has no row at all, so an insert is attempted first; a concurrent insert
     * loses the {@code _id} race and falls through to the CAS path.
     */
    public boolean tryAcquire(String owner, Instant now, Instant expiresAt) {
        Query byId = Query.query(Criteria.where(F_ID).is(LOCK_ID));
        if (!mongoTemplate.exists(byId, SchemaMigrationLockDocument.class)) {
            try {
                mongoTemplate.insert(SchemaMigrationLockDocument.builder()
                        .id(LOCK_ID)
                        .owner(owner)
                        .acquiredAt(now)
                        .expiresAt(expiresAt)
                        .build());
                return true;
            } catch (DuplicateKeyException dup) {
                log.debug("Schema-migration lock: concurrent insert — falling through to CAS");
            }
        }
        // CAS: free (owner absent/null) or the lease has run out.
        Criteria free = new Criteria().orOperator(
                Criteria.where(F_OWNER).is(null),
                Criteria.where(F_OWNER).exists(false));
        Criteria expired = new Criteria().orOperator(
                Criteria.where(F_EXPIRES_AT).is(null),
                Criteria.where(F_EXPIRES_AT).lte(now));
        Query query = new Query(new Criteria().andOperator(
                Criteria.where(F_ID).is(LOCK_ID),
                new Criteria().orOperator(free, expired)));
        Update update = new Update()
                .set(F_OWNER, owner)
                .set(F_ACQUIRED_AT, now)
                .set(F_EXPIRES_AT, expiresAt);
        return mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                SchemaMigrationLockDocument.class) != null;
    }

    /**
     * Extends the lease. Only succeeds while {@code owner} still holds it —
     * a {@code false} return means the lock was stolen and the caller is no
     * longer authoritative.
     */
    public boolean renew(String owner, Instant expiresAt) {
        Query query = Query.query(Criteria.where(F_ID).is(LOCK_ID).and(F_OWNER).is(owner));
        Update update = new Update().set(F_EXPIRES_AT, expiresAt);
        return mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                SchemaMigrationLockDocument.class) != null;
    }

    /** Releases the lock, but only when {@code owner} still holds it. */
    public boolean release(String owner) {
        Query query = Query.query(Criteria.where(F_ID).is(LOCK_ID).and(F_OWNER).is(owner));
        Update update = new Update()
                .set(F_OWNER, null)
                .set(F_ACQUIRED_AT, null)
                .set(F_EXPIRES_AT, null);
        return mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                SchemaMigrationLockDocument.class) != null;
    }
}
