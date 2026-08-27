package de.mhus.vance.shared.marvin;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Marvin's task tree. A node belongs to the process that grew it, so the
 * project is reached through {@code processId} — a cascade.
 */
@Component
@RequiredArgsConstructor
public class MarvinNodeProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return "marvin-nodes";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(MarvinNodeDocument.class));
    }

    /** Cascade — must precede {@code think-processes}. */
    @Override
    public int order() {
        return 300;
    }

    @Override
    public long count(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null ? 0 : mongoTemplate.count(query, MarvinNodeDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null
                ? 0
                : mongoTemplate.remove(query, MarvinNodeDocument.class).getDeletedCount();
    }

    private @Nullable Query scope(String tenantId, String projectId) {
        List<String> processIds = thinkProcessService.findIdsByProject(tenantId, projectId);
        if (processIds.isEmpty()) {
            return null;
        }
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("processId").in(processIds));
    }
}
