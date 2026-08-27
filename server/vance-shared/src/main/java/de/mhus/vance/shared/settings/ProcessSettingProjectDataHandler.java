package de.mhus.vance.shared.settings;

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
 * Settings scoped to a single think process — the innermost layer of the
 * cascade.
 *
 * <p>Separate from {@link SettingProjectDataHandler} even though it is the same
 * collection, because the two answer different questions and, decisively, must
 * run at different times: these rows are found through the project's processes
 * and therefore before those processes are deleted.
 *
 * <p>Nothing to rename: the reference is a process id, and a project rename
 * does not renumber processes.
 */
@Component
@RequiredArgsConstructor
public class ProcessSettingProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return "settings-process-scope";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(SettingDocument.class));
    }

    /** Cascade — must precede {@code think-processes}. */
    @Override
    public int order() {
        return 400;
    }

    @Override
    public long count(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null ? 0 : mongoTemplate.count(query, SettingDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null
                ? 0
                : mongoTemplate.remove(query, SettingDocument.class).getDeletedCount();
    }

    private @Nullable Query scope(String tenantId, String projectId) {
        List<String> processIds = thinkProcessService.findIdsByProject(tenantId, projectId);
        if (processIds.isEmpty()) {
            return null;
        }
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("referenceType").is(SettingService.SCOPE_THINK_PROCESS)
                .and("referenceId").in(processIds));
    }
}
