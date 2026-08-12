package de.mhus.vance.shared.schema.migrations;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drops {@code tool_usage_stats} so it can be rebuilt per role.
 *
 * <p>The collection holds rolling demand counters that only break ties in
 * the tool-surface budget ({@code planning/tool-surface-budget.md}). Two
 * changes make the existing rows unusable rather than merely outdated:
 *
 * <ol>
 *   <li><b>New key.</b> {@code recipeName} joins the unique key, because
 *       demand is role-specific. Existing rows have no role at all, so they
 *       cannot be attributed to one — guessing would attribute a coding
 *       worker's 153 {@code file_read} calls to whoever queries next.</li>
 *   <li><b>Wrong numbers.</b> Until 2026-08-12 the recorder counted the
 *       delegated leg of a wrapper call as its own call, so every
 *       {@code file_*} / {@code exec_*} pair was double-counted.</li>
 * </ol>
 *
 * <p>Dropping is the honest option: a wrong tie-breaker is worse than none,
 * the data is hours old, and nothing else reads it. The old unique index
 * disappears with the collection — Spring's auto-index-creation then builds
 * the new one on first write.
 *
 * <p>Idempotent: dropping a collection that is already gone is a no-op.
 */
public final class Migrator_2026_08_12_002_ToolUsageStatsPerRole implements SchemaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(Migrator_2026_08_12_002_ToolUsageStatsPerRole.class);

    private static final String COLLECTION = "tool_usage_stats";

    @Override
    public void up(SchemaMigrationContext context) {
        if (!context.mongoTemplate().collectionExists(COLLECTION)) {
            log.info("{}: '{}' does not exist — nothing to drop",
                    context.migrationId(), COLLECTION);
            return;
        }
        long before = context.mongoTemplate().getCollection(COLLECTION).countDocuments();
        context.mongoTemplate().getCollection(COLLECTION).drop();
        log.info("{}: dropped '{}' with {} row(s) — counters restart per role",
                context.migrationId(), COLLECTION, before);
    }
}
