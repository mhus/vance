package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Gives existing threads their participants and read state.
 *
 * <p>Five fields arrive with the discussion — {@code messages},
 * {@code reactions}, {@code readBy}, {@code participants}, {@code unreadFor}.
 * Four of them default to an empty list and would be harmless as absent
 * fields; they are written anyway so that a document read back looks like one
 * the current code wrote, and so the badge index has something to index.
 *
 * <p><b>The one real decision is {@code unreadFor}.</b> Seeding everything
 * empty would leave the badge at zero after the deploy — including for asks
 * that are lighting it up today, so a user would see "0" and miss a decision
 * someone is waiting on. Therefore: <b>PENDING threads become unread for their
 * assignee</b>, everything else stays empty (an answered, dismissed or archived
 * thread is not asking for attention, which is exactly what it looked like
 * before). Continuity beats quiet.
 *
 * <p>{@code readBy} stays empty in both cases. Claiming an existing thread has
 * been read would be an invention: nothing in the old data records who looked
 * at what, and a wrong "already read" is the one error that cannot be noticed.
 *
 * <p>{@code participants} is derived from {@code originatorUserId} +
 * {@code assignedToUserId}. This is the one place where deriving it is right —
 * for historical rows there is no other source, and invitations never existed.
 * From here on the field is authoritative and explicit.
 *
 * <p>Idempotent through a self-emptying filter: only documents without a
 * {@code participants} field are touched, so a second run matches nothing.
 *
 * <p><b>{@code runOnBaseline}, and it has to travel with the rename in
 * {@code 2026-08-23_002}.</b> That one is on the baseline path for a stated
 * reason: a database restored from before the anchor carries no marker, looks
 * new from here, and gets baselined. On exactly that path the rename runs and
 * brings the rows across — while this backfill, merely stamped, would leave
 * {@code unreadFor} absent on every one of them. A missing {@code unreadFor}
 * does read as "nothing unread", and that is precisely the problem: the badge
 * shows 0 for asks that are lighting it up today, which is the outcome named
 * above as the one to avoid, and nothing retries it later. Two adjacent
 * migrations must not reason from opposite premises about what a baselined
 * database is. On a genuinely new database the self-emptying filter makes this
 * one query.
 *
 * <p>See {@code planning/maximegalon.md} §8.
 */
public final class Migrator_2026_08_23_003_MaximegalonThreadFields implements SchemaMigration {

    private static final String COLLECTION = "maximegalon_threads";

    /**
     * Rows per bulk write. The migrator runs between the Mongo infrastructure
     * and the repository layer, so the boot waits on it — one round-trip per
     * row would make an installation's start time a function of its inbox size.
     * Chunked rather than one big batch so neither the cursor nor the pending
     * updates are held in memory in full.
     */
    private static final int CHUNK = 1000;

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_23_003_MaximegalonThreadFields.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO,
                    "'" + COLLECTION + "' is absent — nothing to backfill");
            return;
        }

        long modified = 0;
        int buffered = 0;
        BulkOperations bulk = newBulk(context);
        try (var cursor = context.mongoTemplate().getDb().getCollection(COLLECTION)
                .find(new Document("participants", new Document("$exists", false)))
                .projection(new Document("_id", 1)
                        .append("originatorUserId", 1)
                        .append("assignedToUserId", 1)
                        .append("status", 1))
                .cursor()) {
            while (cursor.hasNext()) {
                Document row = cursor.next();
                bulk.updateOne(
                        Query.query(Criteria.where("_id").is(row.get("_id"))),
                        updateFor(row));
                if (++buffered >= CHUNK) {
                    modified += bulk.execute().getModifiedCount();
                    bulk = newBulk(context);
                    buffered = 0;
                }
            }
        }
        if (buffered > 0) {
            modified += bulk.execute().getModifiedCount();
        }

        log.log(System.Logger.Level.INFO,
                "backfilled thread fields on " + modified + " threads without participants");
    }

    private static BulkOperations newBulk(SchemaMigrationContext context) {
        return context.mongoTemplate().bulkOps(BulkOperations.BulkMode.UNORDERED, COLLECTION);
    }

    /** The write for one row: participants derived, unread only for open asks. */
    private static Update updateFor(Document row) {
        List<String> participants = new ArrayList<>();
        addIfPresent(participants, row.getString("originatorUserId"));
        addIfPresent(participants, row.getString("assignedToUserId"));

        List<String> unreadFor = new ArrayList<>();
        if ("PENDING".equals(row.getString("status"))) {
            addIfPresent(unreadFor, row.getString("assignedToUserId"));
        }

        return new Update()
                .set("participants", participants)
                .set("unreadFor", unreadFor)
                .set("readBy", List.of())
                .set("reactions", List.of())
                .set("messages", List.of());
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }
}
