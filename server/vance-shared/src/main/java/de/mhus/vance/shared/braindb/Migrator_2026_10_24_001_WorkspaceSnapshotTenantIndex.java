package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.springframework.data.mongodb.core.index.IndexInfo;

/**
 * Drops the superseded {@code workspace_snapshots} index
 * {@code project_dirname_idx}.
 *
 * <p>It was unique on {@code (projectId, dirName)} without the tenant, so a
 * project name used in two tenants could hold a snapshot of a given RootDir in
 * only one of them. The replacement {@code tenant_project_dirname_idx} adds
 * the tenant and ships under a new name — a shipped index name cannot change
 * its key pattern (IndexKeySpecsConflict, raised by
 * {@code auto-index-creation} before any migration runs; same reasoning as
 * {@link Migrator_2026_08_24_002_MegadodoFeedIndexes}). By the time this runs
 * the replacement already exists.
 *
 * <p>Idempotent: the name is looked up before dropping.
 */
public final class Migrator_2026_10_24_001_WorkspaceSnapshotTenantIndex implements SchemaMigration {

    private static final String COLLECTION = "workspace_snapshots";
    private static final String RETIRED = "project_dirname_idx";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(Migrator_2026_10_24_001_WorkspaceSnapshotTenantIndex.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO, "'" + COLLECTION + "' does not exist — no index to retire");
            return;
        }
        var indexOps = context.mongoTemplate().indexOps(COLLECTION);
        boolean present =
                indexOps.getIndexInfo().stream().map(IndexInfo::getName).anyMatch(RETIRED::equals);
        if (present) {
            indexOps.dropIndex(RETIRED);
        }
        log.log(
                System.Logger.Level.INFO,
                present
                        ? "dropped superseded index " + RETIRED
                        : "index " + RETIRED + " not present — nothing to drop");
    }
}
