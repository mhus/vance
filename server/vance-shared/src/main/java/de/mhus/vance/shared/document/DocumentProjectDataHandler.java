package de.mhus.vance.shared.document;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.storage.StorageService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The project's documents — rows plus the blobs behind them.
 *
 * <p>A document row is small; its content is a blob addressed by
 * {@code storageId}. Deleting only the rows would leave those blobs with
 * nothing pointing at them, which is survivable — the orphan sweep reclaims
 * them eventually — but only on a brain, and only after the grace period. So
 * the blobs are scheduled for deletion here, and the sweep stays what it always
 * was: the backstop, not the plan.
 *
 * <p>Two kinds of row have no blob and are handled by saying nothing about
 * them: mounted documents under {@code _ext} (their bytes live in someone
 * else's system and are not ours to delete) and empty documents. Both simply
 * carry no {@code storageId}.
 *
 * <p><b>Not routed through {@link DocumentService#delete}.</b> That path is
 * per-document and does considerably more — lock checks, archive cascade,
 * change events, writer identity. Firing it thousands of times to demolish a
 * project would publish a change event per file for subscribers of a project
 * that is being removed. Here the whole project goes at once; the archives are
 * a handler of their own.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final StorageService storageService;

    @Override
    public String id() {
        return "documents";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(DocumentDocument.class));
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(tenantId, projectId), DocumentDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        int blobs = scheduleBlobDeletion(tenantId, projectId);
        long removed = mongoTemplate.remove(scope(tenantId, projectId), DocumentDocument.class)
                .getDeletedCount();
        log.info("Project '{}/{}': removed {} document(s), scheduled {} blob(s) for deletion",
                tenantId, projectId, removed, blobs);
        return removed;
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        return mongoTemplate.updateMulti(
                        scope(tenantId, projectId),
                        new Update().set("projectId", newProjectId),
                        DocumentDocument.class)
                .getModifiedCount();
    }

    /**
     * Schedules every blob of the project for deletion, before the rows that
     * name them are gone.
     *
     * <p>Ordering is the point: the {@code storageId} exists only on the row,
     * so a crash between this and the removal leaves blobs marked for deletion
     * while their documents still exist — recoverable, and the soft-delete
     * window is exactly the room for it. The reverse order would lose the
     * pointers outright.
     */
    private int scheduleBlobDeletion(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        query.fields().include("storageId");
        List<DocumentDocument> rows = mongoTemplate.find(query, DocumentDocument.class);
        int scheduled = 0;
        for (DocumentDocument row : rows) {
            String storageId = row.getStorageId();
            if (storageId == null || storageId.isBlank()) {
                continue;
            }
            storageService.delete(storageId);
            scheduled++;
        }
        return scheduled;
    }

    private Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId));
    }
}
