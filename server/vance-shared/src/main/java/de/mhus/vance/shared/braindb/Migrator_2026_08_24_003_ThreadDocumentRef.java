package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.bson.Document;

/**
 * Moves a thread's document reference out of {@code payload} into its own
 * field.
 *
 * <p>The reference used to be a plain map entry written the same way by three
 * producers ({@code inbox_post}, the Milliways inbox handler, and now the
 * Cortex discussion tab). It became a field the moment the inbox stopped being
 * only a filing place and became something one <b>queries</b>: "which threads
 * are about this document" cuts across every {@code MaximegalonType}, and
 * {@code payload} is type-specific by contract.
 *
 * <p>The code was switched over in one step — all three producers write the
 * field, and <b>no reader looks at {@code payload.documentRef} any more</b>.
 * Without this migration every thread created before that switch therefore
 * loses its document link in the inbox detail <em>and</em> is invisible to
 * {@code listByDocument}, which is to say: invisible to exactly the discussion
 * tab whose findability the move was for. The shape is identical on both sides
 * ({@code documentId}/{@code projectId}/{@code path}/{@code title}/
 * {@code mimeType}), so this is a pure move, no field translation.
 *
 * <p><b>Filter, and why it names both fields.</b> A row is touched only when
 * the old form is present <em>and</em> the new one is absent. No document
 * should have both — the new field is new — but {@code $rename} overwrites its
 * target, so a hypothetical row carrying both would have its current reference
 * replaced by the stale one. The filter is also what makes this idempotent: a
 * second run matches nothing.
 *
 * <p><b>{@code runOnBaseline}, travelling with {@code 2026-08-23_002} and
 * {@code _003}.</b> Those two are on the baseline path because a database
 * restored from before the anchor carries no marker, looks new from here, and
 * gets baselined. On exactly that path the rename brings the rows across from
 * {@code inbox_items} — and this one, merely stamped, would leave every one of
 * them with a reference nothing reads. The failure is silent (a thread without
 * a document link looks like a thread that never had one), and no later boot
 * retries it. On a genuinely new database the self-emptying filter makes this
 * one query.
 *
 * <p>See {@code planning/code-review-6.md} S2.
 */
public final class Migrator_2026_08_24_003_ThreadDocumentRef implements SchemaMigration {

    private static final String COLLECTION = "maximegalon_threads";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_24_003_ThreadDocumentRef.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO,
                    "'" + COLLECTION + "' is absent — nothing to move");
            return;
        }

        Document filter = new Document("payload.documentRef", new Document("$exists", true))
                .append("documentRef", new Document("$exists", false));
        Document rename = new Document("$rename",
                new Document("payload.documentRef", "documentRef"));

        long modified = context.mongoTemplate().getDb().getCollection(COLLECTION)
                .updateMany(filter, rename)
                .getModifiedCount();

        log.log(System.Logger.Level.INFO,
                "moved payload.documentRef to documentRef on " + modified + " thread(s)");
    }
}
