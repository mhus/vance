package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Stamps {@code kind: vance-research-source} onto the search-source documents
 * under {@code _vance/config/research/} whose row carries no kind yet.
 *
 * <p>Those documents were written before the kind existed — the source-config
 * refactor ({@code 1e0693441}) moved the configuration from settings to plain
 * YAML documents, and they open in the raw editor until something says what
 * they are. The client routes on the row's {@code kind} first and falls back
 * to the body marker, so the row stamp is enough: opening the document lands
 * in the source form, and the first save writes {@code $meta.kind} into the
 * body as well (the form guarantees the marker).
 *
 * <p>Only rows without a kind are touched — a document that names a different
 * kind says so deliberately, and rewriting its body is not this migration's
 * business. Idempotent: the second run finds nothing.
 *
 * <p>Not {@code runOnBaseline}: a database new enough to be baselined gets its
 * sources from the setup wizard or the templates, and both write the marker
 * since this release.
 */
public final class Migrator_2026_10_03_001_ResearchSourceKind implements SchemaMigration {

    private static final String PATH_PREFIX = "_vance/config/research/";
    private static final String KIND = "vance-research-source";

    @Override
    public void up(SchemaMigrationContext context) {
        Query query = new Query(Criteria.where("path")
                .regex("^" + Pattern.quote(PATH_PREFIX))
                .and("kind")
                .is(null));
        var result = context.mongoTemplate().updateMulti(query, Update.update("kind", KIND), DocumentDocument.class);
        System.getLogger(Migrator_2026_10_03_001_ResearchSourceKind.class.getName())
                .log(
                        System.Logger.Level.INFO,
                        "stamped kind='" + KIND + "' on " + result.getModifiedCount() + " search source document(s)");
    }
}
