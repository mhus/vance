package de.mhus.vance.shared.document;

import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Lazy boot-time migrator that drains the legacy {@code inlineText} column on
 * the {@code documents} and {@code document_archives} collections into the
 * storage service. Runs once per Brain start in a daemon thread — no
 * coordination across pods; the {@code inlineText != null} filter makes the
 * sweep idempotent and resumable, so two pods would compete on individual
 * rows but never corrupt them. Operators running multi-pod clusters should
 * scale to one pod for the duration of the migration.
 *
 * <p>The component is intentionally inside {@code vance-shared} (not
 * {@code vance-brain}) so the Foot test harness and any future service that
 * boots {@code vance-shared} also drains automatically.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentInlineMigrator {

    private final DocumentService documentService;
    private final StorageService storageService;
    private final MongoTemplate mongoTemplate;

    @Value("${vance.document.inline-migration.enabled:true}")
    private boolean enabled;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("doc-inline-migrator");
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleOnBoot() {
        if (!enabled) {
            log.info("Document inline→storage migrator disabled via property");
            return;
        }
        runner.submit(this::runOnce);
        runner.shutdown(); // accept no further tasks; thread terminates after runOnce()
    }

    /**
     * Run a full sweep over both collections. Public so tests can call it
     * directly without going through Spring's lifecycle events.
     *
     * @return summary of the work performed
     */
    public Stats runOnce() {
        long t0 = System.currentTimeMillis();
        long docs = migrateCollection("documents", DocumentDocument.class);
        long archives = migrateCollection("document_archives", DocumentArchiveDocument.class);
        long elapsedMs = System.currentTimeMillis() - t0;
        if (docs > 0 || archives > 0) {
            log.info(
                    "Document inline→storage migration finished: documents={} archives={} elapsedMs={}",
                    docs, archives, elapsedMs);
        } else {
            log.debug("Document inline→storage migration: nothing to do (elapsedMs={})", elapsedMs);
        }
        return new Stats(docs, archives, elapsedMs);
    }

    private <T> long migrateCollection(String collection, Class<T> entityType) {
        long migrated = 0;
        while (true) {
            Query findOne = new Query(Criteria.where("inlineText").ne(null)).limit(1);
            T candidate = mongoTemplate.findOne(findOne, entityType, collection);
            if (candidate == null) break;
            try {
                migrateOne(candidate, collection);
                migrated++;
                if (migrated % 100 == 0) {
                    log.info("Inline→storage migration in progress: collection={} migrated={}",
                            collection, migrated);
                }
            } catch (RuntimeException e) {
                log.error("Failed to migrate one row in collection='{}' — aborting sweep",
                        collection, e);
                // Bail out; the next boot will retry from where we left off.
                break;
            }
        }
        return migrated;
    }

    private <T> void migrateOne(T entity, String collection) {
        InlineSnapshot snap = snapshot(entity);
        if (snap == null) return;

        byte[] bytes = snap.inlineText.getBytes(StandardCharsets.UTF_8);
        DocumentService.ContentWriteResult write = documentService.streamingStoreContent(
                snap.tenantId, snap.path, new ByteArrayInputStream(bytes));

        // Atomic field update — keep the row's other fields (kind, headers,
        // ragDirty, …) intact and only overwrite the columns we need to flip.
        Update update = new Update()
                .set("storageId", write.storageId())
                .set("compressed", write.compressed())
                .set("size", write.originalSize())
                .set("inlineText", null);
        Query byId = new Query(Criteria.where("_id").is(snap.id));
        mongoTemplate.updateFirst(byId, update, collection);
        log.debug("Migrated inline document collection='{}' id='{}' size={} compressed={}",
                collection, snap.id, write.originalSize(), write.compressed());
    }

    private static <T> @Nullable InlineSnapshot snapshot(T entity) {
        if (entity instanceof DocumentDocument doc) {
            String inline = doc.getInlineText();
            if (inline == null) return null;
            return new InlineSnapshot(doc.getId(), doc.getTenantId(), doc.getPath(), inline);
        }
        if (entity instanceof DocumentArchiveDocument arc) {
            String inline = arc.getInlineText();
            if (inline == null) return null;
            return new InlineSnapshot(arc.getId(), arc.getTenantId(), arc.getPath(), inline);
        }
        return null;
    }

    private record InlineSnapshot(String id, String tenantId, String path, String inlineText) {}

    public record Stats(long documentsMigrated, long archivesMigrated, long elapsedMs) {}
}
