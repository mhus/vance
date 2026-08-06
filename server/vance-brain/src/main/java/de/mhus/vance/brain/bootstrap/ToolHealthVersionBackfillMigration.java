package de.mhus.vance.brain.bootstrap;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
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
 * {@link ToolHealthDocument} rows created before the {@code @Version}
 * optimistic-locking guard landed. Same failure mode — and same fix — as
 * {@link DocumentVersionBackfillMigration}.
 *
 * <p>Spring Data MongoDB reads a {@code null} {@code @Version} as "new
 * entity" and routes the next {@code repository.save()} through
 * {@code insertOne}, which collides with the existing {@code _id}. For
 * tool-health that failure is permanent rather than transient: the
 * read-modify-save retry in {@code ToolHealthService} re-reads the very
 * same version-less row, so every attempt fails identically. Observed in
 * the wild as {@code AgrajagChecker raised during triage} and
 * {@code ToolHealth auto-clear failed}, with the practical consequence
 * that a tool marked {@code DEGRADED}/{@code DOWN} could never be flipped
 * back to {@code OK}.
 *
 * <p>Sets {@code version=0} on every row where the field is missing.
 * Idempotent: subsequent boots find nothing to migrate and exit silently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolHealthVersionBackfillMigration {

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void migrate() {
        Query missing = Query.query(Criteria.where("version").exists(false));
        long count = mongoTemplate.count(missing, ToolHealthDocument.class);
        if (count == 0) {
            log.debug("ToolHealth version-backfill migration: no rows missing 'version' — nothing to do");
            return;
        }
        UpdateResult res = mongoTemplate.updateMulti(
                missing, new Update().set("version", 0L), ToolHealthDocument.class);
        log.info("ToolHealth version-backfill migration: initialized 'version=0' on {} row(s) (matched {})",
                res.getModifiedCount(), res.getMatchedCount());
    }
}
