package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
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
 * <p>Not {@code runOnBaseline}: a genuinely new database has no threads to
 * backfill, and being stamped without running is exactly right. Unlike the
 * rename in {@code 2026-08-23_002}, a skipped run here is also recoverable —
 * the fields are additive, and a missing {@code unreadFor} reads as "nothing
 * unread" rather than as a lost collection.
 *
 * <p>See {@code planning/maximegalon.md} §8.
 */
public final class Migrator_2026_08_23_003_MaximegalonThreadFields implements SchemaMigration {

    private static final String COLLECTION = "maximegalon_threads";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_23_003_MaximegalonThreadFields.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO,
                    "'" + COLLECTION + "' is absent — nothing to backfill");
            return;
        }

        List<Document> pending = new ArrayList<>();
        context.mongoTemplate().getDb().getCollection(COLLECTION)
                .find(new Document("participants", new Document("$exists", false)))
                .projection(new Document("_id", 1)
                        .append("originatorUserId", 1)
                        .append("assignedToUserId", 1)
                        .append("status", 1))
                .forEach(pending::add);

        int touched = 0;
        for (Document row : pending) {
            List<String> participants = new ArrayList<>();
            addIfPresent(participants, row.getString("originatorUserId"));
            addIfPresent(participants, row.getString("assignedToUserId"));

            List<String> unreadFor = new ArrayList<>();
            if ("PENDING".equals(row.getString("status"))) {
                addIfPresent(unreadFor, row.getString("assignedToUserId"));
            }

            Update update = new Update()
                    .set("participants", participants)
                    .set("unreadFor", unreadFor)
                    .set("readBy", List.of())
                    .set("reactions", List.of())
                    .set("messages", List.of());
            context.mongoTemplate().updateFirst(
                    Query.query(Criteria.where("_id").is(row.get("_id"))),
                    update, COLLECTION);
            touched++;
        }
        log.log(System.Logger.Level.INFO,
                "backfilled thread fields on " + touched + " of " + pending.size()
                        + " threads without participants");
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank() && !target.contains(value)) {
            target.add(value);
        }
    }
}
