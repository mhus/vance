package de.mhus.vance.shared.llmusage;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Token spend — the per-call records and the daily rollups built from them.
 *
 * <p><b>A deliberate trade on delete.</b> These rows are accounting, and
 * deleting them lowers what the tenant's spend history reports for months that
 * are already closed. They go anyway, because the alternative is worse in the
 * direction that matters: a project quota is keyed on the project name, so
 * records left behind are inherited by the next project created under that name
 * and count against a budget that never spent them. History that is missing is
 * visible; a quota silently consumed by a predecessor is not.
 *
 * <p><b>Why rename cannot be an {@code updateMulti}.</b> The daily rollup's
 * {@code _id} <em>is</em> its key: a hash over tenant, day, project, caller,
 * recipe, model, currency and kind. Setting {@code projectId} alone would leave
 * the hash naming the old project, so the next write for that day would compute
 * a different id and insert a <em>second</em> row — the day split in two, both
 * halves claiming to be the total. The rows are therefore re-keyed one by one.
 * The per-call records have a generated id and are the ordinary case.
 */
@Component
@RequiredArgsConstructor
public class LlmUsageProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "llm-usage";
    }

    @Override
    public Set<String> collections() {
        return Set.of(
                mongoTemplate.getCollectionName(LlmUsageDocument.class),
                mongoTemplate.getCollectionName(LlmUsageDailyDocument.class));
    }

    @Override
    public int order() {
        return 1600;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(tenantId, projectId), LlmUsageDocument.class)
                + mongoTemplate.count(scope(tenantId, projectId), LlmUsageDailyDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        return mongoTemplate.remove(scope(tenantId, projectId), LlmUsageDocument.class)
                        .getDeletedCount()
                + mongoTemplate.remove(scope(tenantId, projectId), LlmUsageDailyDocument.class)
                        .getDeletedCount();
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        long changed = mongoTemplate.updateMulti(
                        scope(tenantId, projectId),
                        new Update().set("projectId", newProjectId),
                        LlmUsageDocument.class)
                .getModifiedCount();
        for (LlmUsageDailyDocument row : dailyRows(tenantId, projectId)) {
            String oldBucketId = row.getBucketId();
            row.setProjectId(newProjectId);
            row.setBucketId(targetBucketId(row, newProjectId));
            // New row first: a crash in between leaves a duplicate of one day,
            // which is visible and fixable. The other order loses the day.
            mongoTemplate.save(row);
            mongoTemplate.remove(
                    new Query(Criteria.where("_id").is(oldBucketId)),
                    LlmUsageDailyDocument.class);
            changed++;
        }
        return changed;
    }

    /**
     * Refuses when re-keying would land on a rollup row that already exists.
     *
     * <p>That happens when a previous project of the target name left its
     * accounting behind — an incomplete delete. Saving over it would silently
     * replace one day's totals with another's, and merging fifteen counter
     * fields to guess a combined truth is not something a rename should decide.
     * Deleting the leftovers first is the answer, and it is the operator's.
     */
    @Override
    public @Nullable String renameBlocker(
            String tenantId, String projectId, String newProjectId) {
        for (LlmUsageDailyDocument row : dailyRows(tenantId, projectId)) {
            if (mongoTemplate.exists(
                    new Query(Criteria.where("_id").is(targetBucketId(row, newProjectId))),
                    LlmUsageDailyDocument.class)) {
                return "a daily usage rollup for '" + newProjectId + "' already exists (day "
                        + row.getDay() + ") — leftover accounting from an earlier project of"
                        + " that name; delete it before renaming";
            }
        }
        return null;
    }

    private List<LlmUsageDailyDocument> dailyRows(String tenantId, String projectId) {
        return new ArrayList<>(
                mongoTemplate.find(scope(tenantId, projectId), LlmUsageDailyDocument.class));
    }

    /**
     * The key this row would have under {@code newProjectId} — computed by the
     * document's own {@code bucketId}, never re-derived here. A second hashing
     * of the same material is a second truth waiting to disagree.
     */
    private static String targetBucketId(LlmUsageDailyDocument row, String newProjectId) {
        return LlmUsageDailyDocument.bucketId(
                row.getTenantId(),
                row.getDay(),
                newProjectId,
                row.getCaller(),
                row.getRecipeName(),
                row.getProviderModel(),
                row.getCurrency(),
                row.getKind());
    }

    private Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("projectId").is(projectId));
    }
}
