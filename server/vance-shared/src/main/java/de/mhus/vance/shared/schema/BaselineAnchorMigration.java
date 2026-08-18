package de.mhus.vance.shared.schema;

/**
 * Does nothing, on purpose. This is the <b>anchor</b> that starts the migration
 * timeline of an installation, and it is what keeps the very first real migration
 * from being skipped.
 *
 * <h2>Why an empty migration has to exist</h2>
 * A database without any marker is treated as new and gets baselined at the
 * current version without running anything
 * ({@link SchemaMigrationService}'s baseline path). If the registry were empty there
 * would be nothing to stamp: the version would stay empty, every existing
 * database would still look unseen, and the first real migration shipped later
 * would be baselined away instead of run.
 *
 * <p>With this anchor registered, the first boot of every database — new or
 * existing — writes a {@code BASELINED} marker for {@code 2026-08-12_001}. From
 * then on the database is "known", and every migration added afterwards carries a
 * higher id, is therefore pending, and runs.
 *
 * <p>The anchor is never executed: it is the lowest id there is, so no database
 * can ever sit below it. It stays registered forever — removing it would change
 * no version, but it records where this installation's timeline began.
 *
 * <p>Every database gets one, under an id of its own: the brain's registry
 * anchors at {@code 2026-08-12_001}, the kit store's at {@code 2026-08-18_001}.
 * The class carries no date because it does nothing — what is dated is the
 * point in a particular database's timeline, and that lives in the registry.
 */
public final class BaselineAnchorMigration implements SchemaMigration {

    @Override
    public void up(SchemaMigrationContext context) {
        // Intentionally empty — see the class comment.
    }
}
