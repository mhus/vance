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
 * Archived document versions and their blobs.
 *
 * <p>Its own handler rather than part of {@code documents}, because the two are
 * genuinely separate stores: an archive survives the deletion of the live
 * document it came from, and the version panel is built from it. In a report,
 * "12 documents, 940 archives" is also the number an operator wants to see
 * before deciding.
 *
 * <p>Blobs go the same way as in {@link DocumentProjectDataHandler}: an archive
 * owns its {@code storageId} outright — the archive path pointer-moves it out
 * of the live row and never shares — so scheduling it here deletes nothing the
 * live document still needs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentArchiveProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final StorageService storageService;

    @Override
    public String id() {
        return "document-archives";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(DocumentArchiveDocument.class));
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(tenantId, projectId), DocumentArchiveDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        int blobs = scheduleBlobDeletion(tenantId, projectId);
        long removed = mongoTemplate
                .remove(scope(tenantId, projectId), DocumentArchiveDocument.class)
                .getDeletedCount();
        log.info("Project '{}/{}': removed {} archive(s), scheduled {} blob(s) for deletion",
                tenantId, projectId, removed, blobs);
        return removed;
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        return mongoTemplate.updateMulti(
                        scope(tenantId, projectId),
                        new Update().set("projectId", newProjectId),
                        DocumentArchiveDocument.class)
                .getModifiedCount();
    }

    private int scheduleBlobDeletion(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        query.fields().include("storageId");
        List<DocumentArchiveDocument> rows =
                mongoTemplate.find(query, DocumentArchiveDocument.class);
        int scheduled = 0;
        for (DocumentArchiveDocument row : rows) {
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
