package de.mhus.vance.shared.enginemessage;

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
 * Messages in flight between think processes. Reached through the processes of
 * the project, hence a cascade.
 *
 * <p>Both ends count. A message is matched when <em>either</em> its sender or
 * its target is a process of this project: a cross-project message has one leg
 * on each side, and taking only the target would leave the sender's copy
 * behind — or, worse, delete a message the other project is still waiting for.
 * Matching either end deletes it once, from whichever side goes first.
 */
@Component
@RequiredArgsConstructor
public class EngineMessageProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return "engine-messages";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(EngineMessageDocument.class));
    }

    /** Cascade — must precede {@code think-processes}. */
    @Override
    public int order() {
        return 200;
    }

    @Override
    public long count(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null ? 0 : mongoTemplate.count(query, EngineMessageDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null
                ? 0
                : mongoTemplate.remove(query, EngineMessageDocument.class).getDeletedCount();
    }

    private @Nullable Query scope(String tenantId, String projectId) {
        List<String> processIds = thinkProcessService.findIdsByProject(tenantId, projectId);
        if (processIds.isEmpty()) {
            return null;
        }
        return new Query(Criteria.where("tenantId").is(tenantId)
                .orOperator(
                        Criteria.where("targetProcessId").in(processIds),
                        Criteria.where("senderProcessId").in(processIds)));
    }
}
