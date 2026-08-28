package de.mhus.vance.shared.braindb;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import de.mhus.vance.shared.settings.SettingDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Writes {@code fook.upstream.providerType=github} wherever Fook is
 * sending and nobody ever named a provider.
 *
 * <p><b>The problem is a silent target change.</b> Until now the default
 * provider was {@code github} — so an installation that forwards its
 * reports to a repository may well have <em>never set the setting</em>,
 * because the default already did what it wanted. Moving the default to
 * {@code vancetope} redirects exactly those installations, and the failure
 * is the quiet kind: reports arrive somewhere else and nothing fails.
 *
 * <p>So the default change and this migration are one release. Afterwards
 * the default is read only by installations that never sent anything at
 * all, for which it is the intended answer.
 *
 * <p><b>Scope-local, deliberately.</b> The value is written next to the
 * {@code mode} that made this installation a sender — same
 * {@code (tenantId, referenceType, referenceId)} triple — rather than at
 * some canonical place. The setting form writes both together, so that is
 * where they belong; picking a different layer would be inventing a cascade
 * decision on somebody else's behalf.
 *
 * <p>Rows whose {@code mode} is {@code never} are left alone. They are not
 * senders, nothing about them changes when the default does, and pinning a
 * provider they never use would be noise in their settings.
 *
 * <p><b>Not {@code runOnBaseline}.</b> A database new enough to be
 * baselined has no Fook configuration to protect — this exists solely for
 * settings that predate the default change, and being stamped without
 * running is exactly right.
 *
 * <p>Idempotent through {@code $setOnInsert}: a second run matches the row
 * it wrote and changes nothing. That also makes it safe against a concurrent
 * writer, which a look-then-insert would not be.
 *
 * <p>See {@code planning/fook-vancetope-connector.md} §6.
 */
public final class Migrator_2026_08_28_001_FookProviderTypeExplicit implements SchemaMigration {

    private static final String COLLECTION = "settings";

    private static final String KEY_MODE = "fook.upstream.mode";
    private static final String KEY_PROVIDER_TYPE = "fook.upstream.providerType";
    private static final String MODE_NEVER = "never";
    private static final String GITHUB = "github";

    @Override
    public void up(SchemaMigrationContext context) {
        System.Logger log = System.getLogger(
                Migrator_2026_08_28_001_FookProviderTypeExplicit.class.getName());
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.log(System.Logger.Level.INFO, "'" + COLLECTION + "' is absent — nothing to do");
            return;
        }

        var settings = context.mongoTemplate().getDb().getCollection(COLLECTION);

        List<Document> senders = new ArrayList<>();
        settings.find(Filters.and(
                        Filters.eq("key", KEY_MODE),
                        Filters.exists("value", true),
                        Filters.ne("value", null),
                        Filters.ne("value", MODE_NEVER)))
                .into(senders);
        if (senders.isEmpty()) {
            log.log(System.Logger.Level.INFO,
                    "no installation is forwarding Fook tickets — nothing to pin");
            return;
        }

        Instant now = Instant.now();
        int written = 0;
        for (Document sender : senders) {
            Document scopeKey = new Document()
                    .append("tenantId", sender.getString("tenantId"))
                    .append("referenceType", sender.getString("referenceType"))
                    .append("referenceId", sender.getString("referenceId"))
                    .append("key", KEY_PROVIDER_TYPE);

            Document insert = new Document(scopeKey)
                    .append("value", GITHUB)
                    .append("type", "STRING")
                    .append("description",
                            "Pinned by migration 2026-08-28_001: this installation was "
                                    + "forwarding tickets while the default provider was "
                                    + "github, and the default has since changed.")
                    .append("createdAt", now)
                    .append("updatedAt", now)
                    // Written out because this row is created without going
                    // through the mapper. Spring Data can read a row without
                    // it when the target type is known, but every row the
                    // application writes has one, and a collection where some
                    // rows differ is a difference somebody will chase later.
                    // The literal name is deliberate: a migration is a
                    // historical record and must not follow a later rename.
                    .append("_class", SettingDocument.class.getName());

            var result = settings.updateOne(
                    scopeKey,
                    new Document("$setOnInsert", insert),
                    new UpdateOptions().upsert(true));
            if (result.getUpsertedId() != null) written++;
        }

        log.log(System.Logger.Level.INFO,
                "pinned fook.upstream.providerType=github in " + written + " scope(s)");
    }
}
