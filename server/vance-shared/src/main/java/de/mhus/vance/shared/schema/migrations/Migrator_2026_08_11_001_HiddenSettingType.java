package de.mhus.vance.shared.schema.migrations;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import de.mhus.vance.shared.settings.SettingDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Re-types the settings that an authored {@code {{secret:…}}} reference has to be
 * able to resolve from {@link SettingType#PASSWORD} to
 * {@link SettingType#HIDDEN}.
 *
 * <h2>Why</h2>
 * Before the PASSWORD/HIDDEN split every secret was PASSWORD and the reference
 * resolver handed all of them out. It now refuses PASSWORD, so credentials that a
 * tool document resolves at call time — SMTP/IMAP passwords, the Jira tokens —
 * stop working on existing installations until they are re-typed. New setups are
 * already correct: the bundled forms declare {@code settingType: HIDDEN} and
 * {@code TemplateApplier} writes HIDDEN. See
 * {@code planning/setting-type-hidden.md} §10.
 *
 * <p>Only the type changes. Both encrypted types share the ciphertext format, so
 * no value is re-encrypted and nothing has to be re-entered.
 *
 * <h2>Why a positive list, and why it is short</h2>
 * The design sketch wanted to be <em>evidence-based</em>: read the
 * {@code {{secret:…}}} references out of the {@code _vance/server-tools/}
 * documents and re-type exactly the keys they name. That is not possible here —
 * document content lives in {@code StorageService} behind
 * {@code DocumentDocument.storageId}, and a migration only gets
 * {@code MongoTemplate} (spec §3.1), so the content is out of reach.
 *
 * <p>The fallback is a list of keys whose reference use is <em>documented</em>:
 * the bundled SMTP/IMAP manuals spell out {@code {{secret:project:smtp.password}}}
 * and friends, and the bundled Jira setting form now declares its two resolvable
 * tokens as HIDDEN.
 *
 * <p>It is deliberately a <b>positive</b> list rather than the inverse
 * ("everything except the known compiled-read keys"). Both are assumption-based,
 * but they fail in opposite directions: a pattern missing from this list leaves a
 * setting PASSWORD, and the next resolve says so by name — recoverable. A key
 * missing from an exclusion list would silently expose a real secret to every
 * agent — not recoverable. Kit-installed credentials cannot be enumerated (their
 * key names live in third-party kit repositories) and are therefore left to that
 * named error.
 *
 * <p>Explicitly <b>not</b> migrated, because compiled server code reads them by a
 * fixed key and they must stay unreadable through a reference:
 * {@code ai.provider.*}, {@code vault.*}, {@code office.jwtSecret},
 * {@code research.endpoint.*.apiKey}, {@code web.serper.apiKey},
 * {@code fook.upstream.github.token}, {@code oauth.*}. None of them can match the
 * patterns below; the assertion is restated in
 * {@code Migrator_2026_08_11_001_HiddenSettingTypeTest}.
 *
 * <p>Idempotent through the {@code type: PASSWORD} filter — after the first run
 * nothing matches.
 */
@Slf4j
public final class Migrator_2026_08_11_001_HiddenSettingType implements SchemaMigration {

    /** Key prefixes whose reference use the bundled manuals document. */
    static final List<String> PREFIXES = List.of("smtp.", "imap.");

    /** Exact keys the bundled setting forms declare as reference-resolvable. */
    static final List<String> EXACT_KEYS = List.of(
            "credentials.jira.access_token",
            "credentials.jira.api_token");

    /** How many still-PASSWORD keys to name in the closing report before eliding. */
    private static final int REPORT_LIMIT = 50;

    @Override
    public void up(SchemaMigrationContext context) {
        MongoTemplate mongo = context.mongoTemplate();

        Query referenceRead = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(SettingType.PASSWORD),
                new Criteria().orOperator(keyCriteria())));

        // Name the targets before changing them: re-typing a secret widens who can
        // resolve it, so the boot log carries the audit trail. Keys only, no values.
        for (SettingDocument doc : mongo.find(referenceRead, SettingDocument.class)) {
            log.info("Re-typing PASSWORD -> HIDDEN: tenant='{}' ref='{}:{}' key='{}'",
                    doc.getTenantId(), doc.getReferenceType(), doc.getReferenceId(), doc.getKey());
        }

        UpdateResult result = mongo.updateMulti(
                referenceRead,
                new Update().set("type", SettingType.HIDDEN),
                SettingDocument.class);
        log.info("Re-typed {} setting(s) PASSWORD -> HIDDEN", result.getModifiedCount());

        reportRemaining(mongo);
    }

    private static Criteria[] keyCriteria() {
        List<Criteria> or = new ArrayList<>(PREFIXES.size() + 1);
        for (String prefix : PREFIXES) {
            or.add(Criteria.where("key").regex("^" + Pattern.quote(prefix)));
        }
        or.add(Criteria.where("key").in(EXACT_KEYS));
        return or.toArray(new Criteria[0]);
    }

    /**
     * Lists the PASSWORD settings left over, so an operator whose tool documents
     * reference something outside the patterns above has the exact list instead of
     * discovering it one failed tool call at a time. Most entries are legitimately
     * PASSWORD (provider keys, vault credentials) — this is a checklist, not a
     * defect report.
     */
    private static void reportRemaining(MongoTemplate mongo) {
        List<SettingDocument> remaining = mongo.find(
                Query.query(Criteria.where("type").is(SettingType.PASSWORD)),
                SettingDocument.class);
        if (remaining.isEmpty()) {
            return;
        }
        log.info("{} setting(s) remain PASSWORD (not resolvable through a "
                        + "{{secret:…}} reference). If a tool document, compose manifest or "
                        + "script references one of these, re-type it to HIDDEN by hand:",
                remaining.size());
        remaining.stream().limit(REPORT_LIMIT).forEach(doc ->
                log.info("  still PASSWORD: tenant='{}' ref='{}:{}' key='{}'",
                        doc.getTenantId(), doc.getReferenceType(),
                        doc.getReferenceId(), doc.getKey()));
        if (remaining.size() > REPORT_LIMIT) {
            log.info("  … and {} more", remaining.size() - REPORT_LIMIT);
        }
    }
}
