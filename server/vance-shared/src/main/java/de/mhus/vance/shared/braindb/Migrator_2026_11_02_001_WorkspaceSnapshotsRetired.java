package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;

/**
 * Reports the {@code workspace_snapshots} rows that nothing reads any more.
 *
 * <p>A workspace used to be snapshotted into this collection when its project was suspended and
 * restored from it on the next start. Workspaces now stay on the disk of the pod that ran the
 * project and are never moved, so the snapshots are no longer written or restored. A project that
 * was suspended before the change has its folder removed and its content only here: it starts with
 * an empty workspace, and this migration is the one place that says so.
 *
 * <p>The rows are left untouched — they are the only copy of that content — and the collection
 * can be dropped by hand once nobody needs it. Read-only and therefore idempotent.
 */
public final class Migrator_2026_11_02_001_WorkspaceSnapshotsRetired implements SchemaMigration {

    private static final String COLLECTION = "workspace_snapshots";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(Migrator_2026_11_02_001_WorkspaceSnapshotsRetired.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO, "'" + COLLECTION + "' does not exist — nothing was snapshotted");
            return;
        }
        long rows = context.mongoTemplate().getCollection(COLLECTION).countDocuments();
        if (rows == 0) {
            log.log(System.Logger.Level.INFO, "'" + COLLECTION + "' is empty — no suspended workspace is affected");
            return;
        }
        log.log(
                System.Logger.Level.WARNING,
                rows + " workspace snapshot row(s) in '" + COLLECTION + "' are no longer restored: projects that were"
                        + " suspended with a snapshot start with an empty workspace. The rows are left in place as"
                        + " the only copy of that content; drop the collection when it is no longer needed.");
    }
}
