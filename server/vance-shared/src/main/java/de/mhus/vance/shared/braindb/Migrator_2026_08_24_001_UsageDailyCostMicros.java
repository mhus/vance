package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.List;
import org.bson.Document;

/**
 * Moves the day bucket's amounts from {@code double} to integer micro-units.
 *
 * <p>The bucket is the billing record and it is accumulated with {@code $inc},
 * once per attempt, for as long as the row lives — and once the per-call detail
 * rows have aged out it is the only record left. Floating-point accumulation is
 * lossy and order-dependent, which is tolerable for a diagnostic counter and
 * not for the number an invoice is read off. See
 * {@code LlmUsageDailyDocument}.
 *
 * <p>Renames five fields and drops the old ones, in one pipeline update:
 *
 * <pre>
 *   costInput      → costInputMicros       (× 1e6, rounded)
 *   costOutput     → costOutputMicros
 *   costCacheRead  → costCacheReadMicros
 *   costCacheWrite → costCacheWriteMicros
 *   costTotal      → costTotalMicros
 *   costFailed     → costFailedMicros
 * </pre>
 *
 * <p><b>The conversion is not exact and cannot be.</b> What is in the old field
 * is already the result of double addition; rounding it to micro-units keeps
 * the value that was there and stops the drift from growing. That is the whole
 * available guarantee, and it is why this runs now rather than after the
 * collection has aged.
 *
 * <p>Idempotent through a self-emptying filter: only rows that still carry
 * {@code costTotal} are touched. Not {@code runOnBaseline} — the collection is
 * new enough that a database old enough to be baselined does not have it, and a
 * skipped run is visible (amounts read as zero) rather than silently wrong.
 */
public final class Migrator_2026_08_24_001_UsageDailyCostMicros implements SchemaMigration {

    private static final String COLLECTION = "llm_usage_daily";

    /** Old field → new field. Order is irrelevant; the pipeline sets them all at once. */
    private static final List<String[]> RENAMES = List.of(
            new String[] {"costInput", "costInputMicros"},
            new String[] {"costOutput", "costOutputMicros"},
            new String[] {"costCacheRead", "costCacheReadMicros"},
            new String[] {"costCacheWrite", "costCacheWriteMicros"},
            new String[] {"costTotal", "costTotalMicros"},
            new String[] {"costFailed", "costFailedMicros"});

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_24_001_UsageDailyCostMicros.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO,
                    "'" + COLLECTION + "' is absent — nothing to convert");
            return;
        }

        Document set = new Document();
        List<String> unset = new java.util.ArrayList<>(RENAMES.size());
        for (String[] rename : RENAMES) {
            // $round on a missing field yields null, so guard with $ifNull:
            // a row that never had costFailed must end up with 0, not null,
            // or the next $inc fails on a non-numeric field.
            set.append(rename[1], new Document("$round",
                    List.of(new Document("$multiply",
                                    List.of(new Document("$ifNull", List.of("$" + rename[0], 0)),
                                            1_000_000)),
                            0)));
            unset.add(rename[0]);
        }

        long modified = context.mongoTemplate().getDb().getCollection(COLLECTION)
                .updateMany(
                        new Document("costTotal", new Document("$exists", true)),
                        List.of(new Document("$set", set), new Document("$unset", unset)))
                .getModifiedCount();

        log.log(System.Logger.Level.INFO,
                "converted " + modified + " usage day buckets to integer micro-units");
    }
}
