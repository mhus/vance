package de.mhus.vance.brain.bootstrap;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.document.DocumentDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * One-shot startup migration that backfills the {@code version} field on
 * {@link DocumentDocument} rows created before the {@code @Version}
 * optimistic-locking guard landed (2026-05-25).
 *
 * <p>Spring Data MongoDB treats a {@code null} {@code @Version} value as
 * "new entity" and routes the next {@code repository.save()} call through
 * {@code insertOne}, which then collides with the unique {@code _id} index
 * — observed in the wild as a {@code MongoWriteException: duplicate key
 * error} on PUT {@code /documents/{id}}.
 *
 * <p>Sets {@code version=0} on every document where the field is missing.
 * Idempotent: subsequent boots find nothing to migrate and exit silently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVersionBackfillMigration {

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void migrate() {
        Query missing = Query.query(Criteria.where("version").exists(false));
        long count = mongoTemplate.count(missing, DocumentDocument.class);
        if (count == 0) {
            log.debug("Document version-backfill migration: no rows missing 'version' — nothing to do");
            return;
        }
        UpdateResult res = mongoTemplate.updateMulti(
                missing, new Update().set("version", 0L), DocumentDocument.class);
        log.info("Document version-backfill migration: initialized 'version=0' on {} document(s) "
                        + "(matched {})",
                res.getModifiedCount(), res.getMatchedCount());
    }
}
