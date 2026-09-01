package de.mhus.vance.shared.braindb;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.mhus.vance.shared.integration.IntegrationTokenDocument;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Moves an integration token's single {@code scopeProfile} into the
 * {@code scopeProfiles} list.
 *
 * <p><b>What breaks without it is the revocation channel.</b> A row written
 * before the field became a list has no {@code scopeProfiles}, so the new code
 * reads an empty capability set — and the token dialog lists a token by
 * filtering on exactly that. The token keeps authenticating (liveness is
 * {@code jti} plus tenant/user/project, none of which changed) but drops out of
 * the only list with a Revoke button on it. A live credential that cannot be
 * revoked through the product is the one failure this whole subsystem exists to
 * prevent, and it is silent: the token list simply looks shorter.
 *
 * <p><b>Not a {@code $rename}.</b> That would leave a bare string sitting in a
 * field the mapper reads as a list, which is a different kind of broken —
 * readable by Mongo, unreadable by Spring Data. The value has to become a
 * one-element array, so it is read and rewritten.
 *
 * <p><b>Not {@code runOnBaseline}.</b> Integration tokens are newer than the
 * anchor, so a database old enough to be baselined has none — and being stamped
 * without running is exactly right. Nothing here can be wrong on a database
 * that never had the old shape.
 *
 * <p>Idempotent through a self-emptying filter: the second run finds no row
 * with the old field.
 *
 * <p><b>The cursor is drained before anything is written.</b> Updating inside
 * the loop would {@code $unset} the very field the cursor selects on, and
 * MongoDB makes no promise that an unindexed scan will not skip a document
 * changed mid-iteration. Skipping is not self-healing here — the migration is
 * stamped {@code APPLIED} and never runs again, so the row would keep the old
 * shape forever and sit in exactly the unrevocable state described above.
 * There are a handful of these rows; reading them first costs nothing.
 */
public final class Migrator_2026_09_01_001_IntegrationTokenProfiles implements SchemaMigration {

    private static final String OLD_FIELD = "scopeProfile";
    private static final String NEW_FIELD = "scopeProfiles";

    @Override
    public void up(SchemaMigrationContext context) {
        String collection = context.mongoTemplate()
                .getCollectionName(IntegrationTokenDocument.class);
        var rows = context.mongoTemplate().getCollection(collection);

        List<Document> legacyRows = rows.find(Filters.exists(OLD_FIELD))
                .into(new ArrayList<>());

        for (Document row : legacyRows) {
            Object legacy = row.get(OLD_FIELD);
            // A blank or non-string value is not worth carrying over — the
            // token it belongs to opens nothing either way, and inventing a
            // capability for it would be worse than leaving it empty.
            List<String> profiles = legacy instanceof String s && !s.isBlank()
                    ? List.of(s)
                    : List.of();
            rows.updateOne(
                    Filters.eq("_id", row.get("_id")),
                    Updates.combine(
                            Updates.set(NEW_FIELD, profiles),
                            Updates.unset(OLD_FIELD)));
        }
    }
}
