package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.List;
import org.springframework.data.mongodb.core.index.IndexInfo;

/**
 * Drops the two superseded {@code megadodo_events} indexes, {@code feed_idx}
 * and {@code trace_idx}.
 *
 * <p>Both were reshaped rather than removed: {@code feed_idx} gained the
 * {@code _id} tie-break the paging sort needs, {@code trace_idx} gained
 * {@code projectId} between the trace and the sort key. Neither could keep its
 * name. A shipped index name is effectively immutable — Mongo answers a
 * re-create under the same name with a different key pattern with
 * IndexKeySpecsConflict, and with {@code auto-index-creation: true} that lands
 * while the mapping context comes up, i.e. <em>before</em> this migration could
 * possibly run (the migrator sits after {@code MongoTemplate}). So the new
 * shapes ship under new names and this migration removes the leftovers.
 *
 * <p>Which means the ordering is the other way round from a usual migration:
 * by the time this runs, the replacements already exist. Nothing is unindexed
 * in between, and a boot that never reaches this migration is merely carrying
 * two dead indexes — wasted writes, not a broken read.
 *
 * <p>Idempotent: the names are looked up before dropping, so a second run — or
 * a database that never had them — does nothing.
 */
public final class Migrator_2026_08_24_002_MegadodoFeedIndexes implements SchemaMigration {

    private static final String COLLECTION = "megadodo_events";
    private static final List<String> RETIRED = List.of("feed_idx", "trace_idx");

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_24_002_MegadodoFeedIndexes.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO,
                    "'" + COLLECTION + "' does not exist — no indexes to retire");
            return;
        }
        var indexOps = context.mongoTemplate().indexOps(COLLECTION);
        List<String> present = indexOps.getIndexInfo().stream()
                .map(IndexInfo::getName)
                .filter(RETIRED::contains)
                .toList();
        for (String name : present) {
            indexOps.dropIndex(name);
        }
        log.log(System.Logger.Level.INFO, present.isEmpty()
                ? "no retired megadodo indexes present — nothing to drop"
                : "dropped superseded megadodo indexes: " + present);
    }
}
