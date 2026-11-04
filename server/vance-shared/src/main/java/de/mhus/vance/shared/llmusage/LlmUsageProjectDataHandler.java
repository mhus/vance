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
 * <p><b>Delete re-attributes, it does not remove.</b> These rows are
 * accounting, and the tenant budget is measured against the sum of every row of
 * the tenant. Deleting a project's rows would hand its spend back to the tenant
 * ceiling — create, spend, delete, repeat. Yet the rows must not stay under the
 * project name either: a project quota is keyed on that name, so a project
 * created later under the same name would inherit a predecessor's spend. The
 * rows therefore move to the tenant level ({@link LlmUsageDailyDocument#NONE}
 * for the rollup, {@code null} for the per-call rows): the tenant keeps what it
 * spent, the name is free.
 *
 * <p>The rollup's {@code _id} hashes the project, so moving means adding the
 * counters onto the tenant-level row of the same key (created if absent) and
 * then removing the old one — see {@link #mergeIntoTenantLevel}.
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
        long moved = mongoTemplate
                .updateMulti(scope(tenantId, projectId), new Update().set("projectId", null), LlmUsageDocument.class)
                .getModifiedCount();
        for (LlmUsageDailyDocument row : dailyRows(tenantId, projectId)) {
            mergeIntoTenantLevel(row);
            moved++;
        }
        return moved;
    }

    /**
     * Adds {@code row} onto the tenant-level rollup of the same key, then
     * removes it. Target first: a crash in between double-counts one row, which
     * errs towards the budget holding; the other order would lose it.
     */
    private void mergeIntoTenantLevel(LlmUsageDailyDocument row) {
        String targetId = targetBucketId(row, LlmUsageDailyDocument.NONE);
        Update update = new Update()
                .setOnInsert("tenantId", row.getTenantId())
                .setOnInsert("day", row.getDay())
                .setOnInsert("projectId", LlmUsageDailyDocument.NONE)
                .setOnInsert("caller", row.getCaller())
                .setOnInsert("recipeName", row.getRecipeName())
                .setOnInsert("providerModel", row.getProviderModel())
                .setOnInsert("currency", row.getCurrency())
                .setOnInsert("kind", row.getKind())
                .inc("calls", row.getCalls())
                .inc("tokensIn", row.getTokensIn())
                .inc("tokensOut", row.getTokensOut())
                .inc("cacheReadTokens", row.getCacheReadTokens())
                .inc("implicitCacheReadTokens", row.getImplicitCacheReadTokens())
                .inc("cacheWriteTokens", row.getCacheWriteTokens())
                .inc("cacheWrite1hTokens", row.getCacheWrite1hTokens())
                .inc("images", row.getImages())
                .inc("costInputMicros", row.getCostInputMicros())
                .inc("costOutputMicros", row.getCostOutputMicros())
                .inc("costCacheReadMicros", row.getCostCacheReadMicros())
                .inc("costCacheWriteMicros", row.getCostCacheWriteMicros())
                .inc("costTotalMicros", row.getCostTotalMicros())
                .inc("callsFailed", row.getCallsFailed())
                .inc("tokensInFailed", row.getTokensInFailed())
                .inc("tokensOutFailed", row.getTokensOutFailed())
                .inc("costFailedMicros", row.getCostFailedMicros())
                .inc("unpricedCalls", row.getUnpricedCalls())
                .inc("unpricedTokensIn", row.getUnpricedTokensIn())
                .inc("unpricedTokensOut", row.getUnpricedTokensOut())
                .inc("unmeasuredCalls", row.getUnmeasuredCalls());
        if (row.getFirstAt() != null) {
            update.min("firstAt", row.getFirstAt());
        }
        if (row.getLastAt() != null) {
            update.max("lastAt", row.getLastAt());
        }
        if (row.getExpiresAt() != null) {
            update.setOnInsert("expiresAt", row.getExpiresAt());
        }
        mongoTemplate.upsert(new Query(Criteria.where("_id").is(targetId)), update, LlmUsageDailyDocument.class);
        mongoTemplate.remove(new Query(Criteria.where("_id").is(row.getBucketId())), LlmUsageDailyDocument.class);
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        long changed = mongoTemplate
                .updateMulti(
                        scope(tenantId, projectId), new Update().set("projectId", newProjectId), LlmUsageDocument.class)
                .getModifiedCount();
        for (LlmUsageDailyDocument row : dailyRows(tenantId, projectId)) {
            String oldBucketId = row.getBucketId();
            row.setProjectId(newProjectId);
            row.setBucketId(targetBucketId(row, newProjectId));
            // New row first: a crash in between leaves a duplicate of one day,
            // which is visible and fixable. The other order loses the day.
            mongoTemplate.save(row);
            mongoTemplate.remove(new Query(Criteria.where("_id").is(oldBucketId)), LlmUsageDailyDocument.class);
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
    public @Nullable String renameBlocker(String tenantId, String projectId, String newProjectId) {
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
        return new ArrayList<>(mongoTemplate.find(scope(tenantId, projectId), LlmUsageDailyDocument.class));
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
        return new Query(
                Criteria.where("tenantId").is(tenantId).and("projectId").is(projectId));
    }
}
