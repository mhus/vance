package de.mhus.vance.shared.database;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Refuses to run against somebody else's database.
 *
 * <p>The brain and the kit store use the same chunked blob storage and the
 * same migration machinery, which is deliberate — but it means a wrong
 * connection string no longer fails loudly. Both would find collections
 * they recognise. The brain's orphan sweep would walk the store's release
 * blobs, the store's migrator would stamp its ids onto the brain's
 * timeline, and the damage would be discovered later, as corruption, in a
 * database nobody thought was involved.
 *
 * <p>So each database is claimed once and checked on every boot after
 * that. An empty database is claimed for whoever arrives first; a
 * database claimed by someone else fails the context, which stops the
 * process before a single document is written.
 *
 * <p><b>Why it cannot be configured.</b> {@link DatabaseOwner} is a bean,
 * not a property. The mistake this guards against is a copied
 * configuration file, so an identity that lives in configuration would be
 * copied along with it and agree with the mistake.
 *
 * <p><b>Order.</b> {@code DatabaseIdentityOrderingPostProcessor} makes
 * every Mongo repository depend on this bean, so the check happens before
 * the repository layer — and before the migrator, which is ordered the
 * same way and depends on this one directly.
 *
 * <p>Spec: {@code specification/schema-migration.md}.
 */
@Service(DatabaseIdentityGuard.BEAN_NAME)
@Slf4j
public class DatabaseIdentityGuard {

    /** Fixed so the ordering post-processor can name it. */
    public static final String BEAN_NAME = "databaseIdentityGuard";

    private final MongoTemplate mongoTemplate;
    private final String expected;
    private final Set<String> foreign;

    public DatabaseIdentityGuard(MongoTemplate mongoTemplate, List<DatabaseOwner> owners) {
        this.mongoTemplate = mongoTemplate;
        DatabaseOwner owner = single(owners);
        this.expected = owner.owner();
        this.foreign = Set.copyOf(owner.foreignCollections());
    }

    /**
     * Exactly one owner, or no boot.
     *
     * <p>None means nobody claimed the database and every process could
     * write to any of them — the state this class exists to end. Two means
     * a package holding a declaration was scanned by an application it does
     * not belong to, which is the mixing itself, one step earlier.
     */
    private static DatabaseOwner single(List<DatabaseOwner> owners) {
        if (owners.isEmpty()) {
            throw new DatabaseIdentityException(
                    "No DatabaseOwner declared. Every application must name the database it owns "
                            + "so a wrong connection string is caught before the first write.");
        }
        if (owners.size() > 1) {
            throw new DatabaseIdentityException("More than one DatabaseOwner declared: "
                    + owners.stream().map(DatabaseOwner::owner).sorted().toList()
                    + ". A database has one owner; two declarations mean one application is "
                    + "scanning a package that belongs to another.");
        }
        return owners.get(0);
    }

    @PostConstruct
    void verify() {
        DatabaseIdentityDocument found = mongoTemplate.findById(
                DatabaseIdentityDocument.SINGLETON_ID, DatabaseIdentityDocument.class);
        if (found == null) {
            claim();
            return;
        }
        if (!expected.equals(found.getOwner())) {
            // Not a warning. Continuing would write brain documents into a
            // store database or the reverse, and both are unrecoverable by
            // the time anyone notices.
            log.error("FATAL: this database belongs to '{}', but this process is '{}'. "
                            + "Refusing to start — check the connection string. The database was "
                            + "claimed at {} by {}.",
                    found.getOwner(), expected, found.getClaimedAt(), found.getClaimedBy());
            throw new DatabaseIdentityException("Database belongs to '" + found.getOwner()
                    + "', this process is '" + expected + "'. Refusing to start so the two are "
                    + "not mixed. If this really is the right database, its identity row is "
                    + "wrong — that is a deliberate, manual repair, not something a boot may do.");
        }
        log.debug("Database identity: '{}' as expected", expected);
    }

    /**
     * Claims an unclaimed database — after making sure it is claimable.
     *
     * <p>Two processes starting against a fresh database race here; the
     * fixed {@code _id} turns that into a duplicate key, and the loser
     * re-reads and compares like everyone else. Insert, not save — a save
     * would overwrite the winner's row instead of colliding with it, which
     * is exactly the check being skipped.
     */
    private void claim() {
        refuseIfItIsSomebodyElses();
        DatabaseIdentityDocument identity = DatabaseIdentityDocument.builder()
                .id(DatabaseIdentityDocument.SINGLETON_ID)
                .owner(expected)
                .claimedAt(Instant.now())
                .claimedBy(host())
                .build();
        try {
            mongoTemplate.insert(identity);
            log.info("Database identity: unclaimed database, claimed for '{}'", expected);
        } catch (DuplicateKeyException e) {
            log.debug("Database identity: another process claimed it first, re-reading");
            verify();
        }
    }

    /**
     * The check that makes the guard useful on day one.
     *
     * <p>Every database that predates this class is unclaimed, so without
     * this the first process to boot would claim whatever it was pointed
     * at — a wrong connection string would be recorded as the truth rather
     * than caught. Collections another application creates are proof
     * enough, and cheap: one listing, no reads.
     */
    private void refuseIfItIsSomebodyElses() {
        if (foreign.isEmpty()) {
            return;
        }
        Set<String> present = mongoTemplate.getCollectionNames();
        List<String> hits = foreign.stream().filter(present::contains).sorted().toList();
        if (hits.isEmpty()) {
            return;
        }
        log.error("FATAL: this database carries no identity yet, but it holds {} — collections "
                        + "this application never creates. Refusing to claim it for '{}'; check "
                        + "the connection string.", hits, expected);
        throw new DatabaseIdentityException("Refusing to claim an unclaimed database for '"
                + expected + "': it holds " + hits + ", which belongs to another application. "
                + "If this really is the right database, claim it deliberately by writing the "
                + "identity row by hand.");
    }

    private static String host() {
        String host = System.getenv("HOSTNAME");
        if (StringUtils.isBlank(host)) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                host = "unknown";
            }
        }
        return host;
    }

    /** What this process claims to be — for tests and for the log line. */
    public String expectedOwner() {
        return expected;
    }

    /** Fails the context. Not caught anywhere: stopping is the point. */
    public static class DatabaseIdentityException extends RuntimeException {

        public DatabaseIdentityException(String message) {
            super(message);
        }

        public DatabaseIdentityException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
