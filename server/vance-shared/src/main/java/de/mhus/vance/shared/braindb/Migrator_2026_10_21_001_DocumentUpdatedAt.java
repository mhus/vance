package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Backfills {@code updatedAt} on existing document rows so the field is
 * non-null from the moment it is queryable. Set to the most recent signal we
 * have for "the document was touched at this point in time":
 * {@code max(lastArchivedAt, createdAt)} — both are written by the same
 * {@link DocumentService} and survive schema changes, so the approximation is
 * stable across restores (a restored row keeps its pre-archive timestamp,
 * which is the closest honest answer). Documents without either field
 * (corruption, very old rows) stay {@code null} and pick up the real value on
 * their next write.
 *
 * <p><b>Why this is not just a one-shot script.</b> The Liver backup tool
 * ({@code planning/user-backup.md}) reads {@code updatedAt} as the file
 * mtime, and the UI shows it as "zuletzt geändert". A {@code null} value
 * reads as "never modified", which would be wrong for every document that
 * existed before this migration ran. The approximation is good enough for
 * both consumers — neither needs exact values for the legacy rows.
 *
 * <p><b>Aggregation pipeline.</b> Mongo 4.2+ allows {@code updateMany} with
 * a pipeline; that is what lets us write the max into a single round trip
 * without loading the docs into the JVM. The pipeline form is also what
 * makes the idempotency argument clean — re-running finds nothing matching
 * {@code updatedAt: {$exists: false}} (the field exists on every touched
 * row after the first run).
 *
 * <p><b>runOnBaseline = true.</b> A database restored from before the anchor
 * is baselined and would otherwise skip this migration, leaving every
 * pre-existing row with {@code null updatedAt}. The migration is a single
 * collection-level {@code updateMany} — cheap on a fresh DB (no rows
 * match), and on a baselined DB it produces a useful approximation that no
 * later boot retries. Without it, those rows would read as "never
 * modified" until their next save, which is the silent-wrong-read failure
 * pattern the baseline mechanism exists to prevent.
 */
public final class Migrator_2026_10_21_001_DocumentUpdatedAt implements SchemaMigration {

    private static final String COLLECTION = "documents";

    @Override
    public void up(SchemaMigrationContext context) {
        MongoTemplate template = context.mongoTemplate();
        // max(lastArchivedAt, createdAt): prefers the archive timestamp when
        // present and newer, falls back to createdAt otherwise. Both fields
        // are nullable on a few legacy rows; the pipeline leaves those
        // untouched (cond evaluates to null → no overwrite, document stays
        // matching the filter until the next save stamps it).
        Document setStage = new Document(
                "$set",
                new Document(
                        "updatedAt",
                        new Document(
                                "$cond",
                                Arrays.asList(
                                        new Document(
                                                "$and",
                                                Arrays.asList(
                                                        new Document("$ne", Arrays.asList("$lastArchivedAt", null)),
                                                        new Document(
                                                                "$gt",
                                                                Arrays.asList("$lastArchivedAt", "$createdAt")))),
                                        "$lastArchivedAt",
                                        "$createdAt"))));
        var result = template.getCollection(COLLECTION)
                .updateMany(new Document("updatedAt", new Document("$exists", false)), List.of(setStage));
        System.getLogger(Migrator_2026_10_21_001_DocumentUpdatedAt.class.getName())
                .log(System.Logger.Level.INFO, "backfilled updatedAt on " + result.getModifiedCount() + " document(s)");
    }
}
