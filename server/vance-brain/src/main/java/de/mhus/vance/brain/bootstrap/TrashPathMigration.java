package de.mhus.vance.brain.bootstrap;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * One-shot startup migration that relocates the soft-delete trash folder from
 * its historical top-level {@code _bin/} prefix to {@code _vance/trash/} (the
 * current {@link DocumentService#TRASH_FOLDER_PREFIX}).
 *
 * <p>Trash moved under {@code _vance/} so it inherits the system-folder
 * protection. Rows created before the move still carry {@code _bin/...} paths;
 * left untouched they would no longer be recognised as trash
 * ({@link DocumentService#isTrash}), so {@code doc_restore} / {@code doc_purge}
 * could not reach them. This rewrites only the {@code path} prefix — the row's
 * {@code name} is the basename ({@code <uuid>_<file>}) and is unchanged.
 *
 * <p>Runs as a Mongo aggregation-pipeline update so each row's suffix is
 * preserved without loading documents into the JVM. Idempotent: after the first
 * run nothing matches the {@code ^_bin/} filter and it exits silently. This is a
 * pure data backfill, so it goes straight to the collection (the standard
 * migration exception to the service-datahoheit rule, mirroring
 * {@link DocumentVersionBackfillMigration}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrashPathMigration {

    private static final String OLD_PREFIX = "_bin/";

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void migrate() {
        Query legacy = Query.query(Criteria.where("path").regex("^" + java.util.regex.Pattern.quote(OLD_PREFIX)));
        long count = mongoTemplate.count(legacy, DocumentDocument.class);
        if (count == 0) {
            log.debug("Trash-path migration: no legacy '{}' rows — nothing to do", OLD_PREFIX);
            return;
        }
        // Aggregation-pipeline update: swap the leading "_bin/" for the current
        // trash prefix while keeping the "<uuid>_<basename>" suffix intact.
        List<Document> pipeline = List.of(new Document("$set", new Document("path",
                new Document("$concat", List.of(
                        DocumentService.TRASH_FOLDER_PREFIX,
                        new Document("$substrCP", List.of(
                                "$path",
                                OLD_PREFIX.length(),
                                new Document("$subtract", List.of(
                                        new Document("$strLenCP", "$path"),
                                        OLD_PREFIX.length())))))))));
        java.util.regex.Pattern prefixMatch =
                java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(OLD_PREFIX));
        UpdateResult res = mongoTemplate.getCollection(
                        mongoTemplate.getCollectionName(DocumentDocument.class))
                .updateMany(new Document("path", prefixMatch), pipeline);
        log.info("Trash-path migration: moved {} document(s) from '{}' to '{}' (matched {})",
                res.getModifiedCount(), OLD_PREFIX, DocumentService.TRASH_FOLDER_PREFIX, res.getMatchedCount());
    }
}
