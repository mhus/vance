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
     *
     * @param runOnBaseline opt out of being skipped by
     *        {@code SchemaMigrationService.baseline()}. Default {@code false},
     *        which is the documented rule: a database with no marker is taken
     *        to be new, so historical transforms have nothing to do there.
     *        <p>Set it to {@code true} for the migrations where that guess
     *        being wrong is <b>not recoverable</b>. The wrong case is a
     *        database that predates the framework and was never booted with
     *        the anchor release — a restored backup, a staging dump, a paused
     *        installation. It is indistinguishable from a new one from here,
     *        and if the migration is the only writer of a value the running
     *        code now reads differently, nothing later puts it right: no
     *        second boot re-tries it, and no edit in the product touches it.
     *        <p>The price of saying {@code true} is that a genuinely new
     *        database runs one no-op query. The price of leaving it
     *        {@code false} wrongly is a silently mis-configured installation,
     *        so the choice is not symmetric. It still is not a default:
     *        the migration has to be cheap on an empty database and
     *        idempotent, and most are neither critical nor free.
     */
    record Registered(String id, Class<? extends SchemaMigration> type, boolean runOnBaseline) {

        /** The ordinary line: baselined away with everything else. */
        public Registered(String id, Class<? extends SchemaMigration> type) {
            this(id, type, false);
        }
    }
}
