package de.mhus.vance.shared.database;

import java.util.Set;

/**
 * Which application a database belongs to.
 *
 * <p>Declared in code rather than in configuration, and that is the whole
 * point: a connection string is the thing people copy between deployments,
 * so an identity read from the same file it is meant to guard would agree
 * with every mistake. A build says "I am the brain" and cannot be talked
 * out of it.
 *
 * <p>Implementations live with the database they name — the brain's in
 * {@code de.mhus.vance.shared.braindb}, the kit store's in
 * {@code de.mhus.vance.ee.store.db} — never in a package another
 * application scans to borrow machinery.
 *
 * <p>Spec: {@code specification/schema-migration.md}.
 */
public interface DatabaseOwner {

    /**
     * Stable short name, stored in the database on first contact and
     * compared on every boot afterwards: {@code brain}, {@code store}.
     *
     * <p>Never change one that has shipped. The value is written into
     * live databases, and a rename would make every one of them look
     * foreign to the code that owns it.
     */
    String owner();

    /**
     * Collections whose presence proves the database is somebody else's.
     *
     * <p>Needed because a claim can only be checked once it exists. Every
     * database that predates this guard is unclaimed, so the first process
     * to boot would claim it — and if that is the wrong process, the guard
     * cements the mistake instead of catching it. That is not theoretical:
     * it happened the first time this was tried, and the store claimed a
     * brain database.
     *
     * <p>So an unclaimed database is examined before it is claimed. Name
     * the few collections that only the <em>other</em> applications create
     * — a handful of stable, unmistakable ones is enough, and this list
     * never needs to be exhaustive: it only has to catch the databases
     * that actually exist.
     *
     * <p>Empty by default, which means "claim anything". Any application
     * that shares a Mongo with another should override it.
     */
    default Set<String> foreignCollections() {
        return Set.of();
    }
}
