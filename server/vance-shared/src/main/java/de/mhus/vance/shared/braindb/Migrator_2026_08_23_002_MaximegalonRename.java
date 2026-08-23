package de.mhus.vance.shared.braindb;

import com.mongodb.MongoNamespace;
import com.mongodb.client.model.RenameCollectionOptions;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;

/**
 * Renames {@code inbox_items} to {@code maximegalon_threads}.
 *
 * <p>The entity behind the inbox grew a discussion thread — participants,
 * messages, read state — and got a name of its own, because {@code Thread}
 * is taken by {@code java.lang.Thread} and a {@code ThreadService} in a tree
 * full of lane schedulers is unreadable. This migration is the rename and
 * <b>nothing else</b>: no new fields, no reshaped rows. The structural
 * change ships separately, so that this one stays reviewable.
 *
 * <p>{@code renameCollection} carries the indexes over, which is the reason
 * it is used instead of copy-and-drop.
 *
 * <p><b>Why {@code runOnBaseline}.</b> This is the only writer of the
 * collection's new location, and the running code only reads the new name. A
 * database restored from before the anchor release carries no marker, looks
 * new from here and would be baselined — the rows would stay in
 * {@code inbox_items} while every query goes to {@code maximegalon_threads},
 * so every inbox in the installation appears <em>empty</em> rather than
 * broken. No later boot re-tries it and no action in the product puts it
 * right. On a genuinely new database the cost is one
 * {@code collectionExists} call.
 *
 * <p>Idempotent: the rename only happens when the old collection is present
 * and the new one is not. A second run finds the source gone and does
 * nothing. The both-present case is left alone deliberately and logged as a
 * warning — that is a half-finished state or a hand-made collection, and
 * merging two collections is not something a rename should decide.
 *
 * <p>Not renamed, on purpose: the {@code inboxItemId} and
 * {@code inboxItemType} fields in {@code permission_requests}, Fook's
 * tickets, Marvin's task-tree nodes and the pending queue. They are foreign
 * keys into this collection, and touching them would turn a rename into a
 * migration across five collections that breaks messages already sitting in
 * the queue at deploy time.
 *
 * <p>See {@code planning/maximegalon.md} §8.
 */
public final class Migrator_2026_08_23_002_MaximegalonRename implements SchemaMigration {

    private static final String OLD = "inbox_items";
    private static final String NEW = "maximegalon_threads";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log =
                System.getLogger(Migrator_2026_08_23_002_MaximegalonRename.class.getName());
        boolean oldExists = context.mongoTemplate().collectionExists(OLD);
        boolean newExists = context.mongoTemplate().collectionExists(NEW);

        if (!oldExists) {
            log.log(System.Logger.Level.INFO,
                    "'" + OLD + "' is absent — nothing to rename");
            return;
        }
        if (newExists) {
            log.log(System.Logger.Level.WARNING,
                    "both '" + OLD + "' and '" + NEW + "' exist — leaving them alone; "
                            + "merging two collections is not this migration's decision");
            return;
        }

        String db = context.mongoTemplate().getDb().getName();
        context.mongoTemplate().getDb().getCollection(OLD)
                .renameCollection(new MongoNamespace(db, NEW),
                        new RenameCollectionOptions().dropTarget(false));
        log.log(System.Logger.Level.INFO,
                "renamed '" + OLD + "' to '" + NEW + "' (indexes carried over)");
    }
}
