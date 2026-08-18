package de.mhus.vance.shared.schema;

import java.util.List;

/**
 * The migrations one application declares for its own database.
 *
 * <p>The machinery here — lease, markers, baseline, ordering against the
 * repository layer — is the same wherever Mongo is used. The <b>list</b> is
 * not: the brain's migrations reshape the brain's collections, the kit
 * store's reshape the store's. Before this interface the list was a static
 * field, so any second application that scanned this package inherited the
 * brain's registry and would have run it against a database those
 * migrations were never written for.
 *
 * <p><b>Silence must never mean "nothing to migrate".</b> That is the
 * inverse of the rule for {@code StorageReferenceSource}, and deliberately
 * so: there, an unheard contributor causes a deletion, so no source means
 * no sweep; here, an unheard contributor causes data to stay in an old
 * shape while the code assumes the new one, so <b>no source at all is a
 * boot failure</b>. Both rules point the same way — the outcome of a
 * mistake must be loud, not quiet.
 *
 * <p>Ids are global across all sources in one application: they are the
 * {@code _id}s of the marker documents and the version scale itself. A
 * duplicate id between two sources fails the boot rather than letting the
 * first one silently win.
 *
 * <p>Spec: {@code specification/schema-migration.md}.
 */
public interface SchemaMigrationSource {

    /**
     * This application's migrations, ascending by id.
     *
     * <p>Order within a source is checked at build time by that source's
     * own test; across sources the service sorts by id, which is why the
     * {@code YYYY-MM-DD_NNN} shape is mandatory.
     */
    List<SchemaMigrationSource.Registered> migrations();

    /** What this source is, for the boot log. Default is the class name. */
    default String sourceName() {
        return getClass().getSimpleName();
    }

    /**
     * One registry line: the id that becomes the marker, and the class to
     * run.
     *
     * <p>The class is instantiated only when the migration is actually
     * pending — a registry of a hundred entries costs one reflective
     * constructor call on the boot that needs it, and none on the others.
     */
    record Registered(String id, Class<? extends SchemaMigration> type) {}
}
