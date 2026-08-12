package de.mhus.vance.shared.schema.migrations;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;

/**
 * Does nothing, on purpose. This is the <b>anchor</b> that starts the migration
 * timeline of an installation, and it is what keeps the very first real migration
 * from being skipped.
 *
 * <h2>Why an empty migration has to exist</h2>
 * A database without any marker is treated as new and gets baselined at the
 * current version without running anything
 * ({@code SchemaMigrationService#baseline()}). If the registry were empty there
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
 */
public final class Migrator_2026_08_12_001_Baseline implements SchemaMigration {

    @Override
    public void up(SchemaMigrationContext context) {
        // Intentionally empty — see the class comment.
    }
}
