package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;

/**
 * Drops {@code event_log}.
 *
 * <p>The collection was Ursa's run journal — TRIGGERED → STARTED →
 * COMPLETED per scheduler tick and hook fire. Two of its readers carried
 * correctness (the one-shot "already fired" anchor and the process→run
 * attribution) and both moved to where that state belongs: the scheduler
 * document and {@code ThinkProcessDocument.triggerOrigin}. The remaining
 * eight readers were display, and the activity feed
 * ({@code megadodo_events}) serves them now. Nothing reads it any more.
 *
 * <p>No backfill: the rows are a log, the feed has been writing its own
 * since the same release, and re-deriving history from a journal with a
 * different vocabulary would produce entries nobody ever saw happen.
 * Dropped, not renamed — a leftover collection with no reader is a trap
 * for whoever opens the database next.
 *
 * <p>Idempotent by nature: dropping a collection that is not there is a
 * no-op in Mongo.
 *
 * <p>See {@code planning/megadodo.md} and
 * {@code specification/public/megadodo-system.md}.
 */
public final class Migrator_2026_08_23_001_DropEventLog implements SchemaMigration {

    private static final String COLLECTION = "event_log";

    @Override
    public void up(SchemaMigrationContext context) {
        boolean existed = context.mongoTemplate().collectionExists(COLLECTION);
        if (existed) {
            context.mongoTemplate().dropCollection(COLLECTION);
        }
        System.getLogger(Migrator_2026_08_23_001_DropEventLog.class.getName())
                .log(System.Logger.Level.INFO,
                        existed
                                ? "dropped the retired 'event_log' collection"
                                : "'event_log' was already absent — nothing to drop");
    }
}
