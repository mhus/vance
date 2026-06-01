package de.mhus.vance.brain.bootstrap;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One-shot startup migration that re-homes system-managed document
 * folders under the canonical {@code _vance/} prefix. The convention
 * was tightened in 2026-06-01 so all kit-installed and engine-loaded
 * artifacts live under {@code _vance/} (matching kit-manifest, hooks,
 * scheduler, workflows, events, tool-templates which already followed
 * it).
 *
 * <p>Affected prefixes:
 * <ul>
 *   <li>{@code manuals/} → {@code _vance/manuals/}</li>
 *   <li>{@code skills/} → {@code _vance/skills/}</li>
 * </ul>
 *
 * <p>Idempotent: subsequent boots find no legacy rows and no-op.
 * Touches every project (user projects + the {@code _tenant} system
 * project + any other system project) — the cascade loader expects
 * the same layout everywhere.
 *
 * <p>Path-only rewrite: no archive shifting, no kit-manifest update.
 * Kit manifests track installed files for future uninstall; if a kit
 * was installed pre-migration its manifest still references the old
 * paths. That's acceptable for v1 — uninstall paths can be fixed by
 * reinstalling the kit after the migration runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemPathPrefixMigration {

    /**
     * Single source of truth for the rewrites. Every system-managed
     * folder convention is migrated to live under {@code _vance/}.
     * Order doesn't matter — paths only have one matching prefix.
     */
    private static final List<PrefixRewrite> REWRITES = List.of(
            new PrefixRewrite("manuals/", "_vance/manuals/"),
            new PrefixRewrite("skills/", "_vance/skills/"),
            new PrefixRewrite("recipes/", "_vance/recipes/"),
            new PrefixRewrite("prompts/", "_vance/prompts/"),
            new PrefixRewrite("wizards/", "_vance/wizards/"),
            new PrefixRewrite("setting_forms/", "_vance/setting_forms/"),
            new PrefixRewrite("strategies/", "_vance/strategies/"),
            new PrefixRewrite("oauth/", "_vance/oauth/"),
            new PrefixRewrite("server-tools/", "_vance/server-tools/"),
            new PrefixRewrite("agrajag/", "_vance/agrajag/"),
            new PrefixRewrite("config/", "_vance/config/"),
            new PrefixRewrite("eddie/", "_vance/eddie/"),
            // Single-file path — the rewrite treats it as a prefix of
            // itself ("ai-models.yaml" starts with "ai-models.yaml"
            // and gets rewritten to "_vance/ai-models.yaml").
            new PrefixRewrite("ai-models.yaml", "_vance/ai-models.yaml"));

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void migrate() {
        long totalUpdated = 0;
        for (PrefixRewrite r : REWRITES) {
            totalUpdated += migrateOne(r);
        }
        if (totalUpdated > 0) {
            log.info("System-path-prefix migration: complete — rewrote {} document path(s)", totalUpdated);
        } else {
            log.debug("System-path-prefix migration: no legacy paths found — nothing to do");
        }
    }

    /**
     * Rewrites every {@code documents} collection entry whose
     * {@code path} starts with {@code from} so it instead starts with
     * {@code to}. Done via a per-document update rather than a single
     * Mongo aggregation pipeline so we keep working with simple
     * find/update semantics that mirror the rest of the bootstrap
     * migrations in this package.
     */
    private long migrateOne(PrefixRewrite rewrite) {
        // Anchored regex — only matches paths that start with the
        // legacy prefix. Pattern.quote() so a literal "/" doesn't get
        // treated as anything special.
        Pattern p = Pattern.compile("^" + Pattern.quote(rewrite.from));
        var filter = Filters.regex("path", p);
        long count = mongoTemplate.getCollection("documents").countDocuments(filter);
        if (count == 0) return 0;

        log.info("System-path-prefix migration: rewriting {} document(s) {} → {}",
                count, rewrite.from, rewrite.to);
        long updated = 0;
        long droppedDuplicates = 0;
        try (MongoCursor<Document> cursor =
                     mongoTemplate.getCollection("documents")
                             .find(filter)
                             .projection(new Document("_id", 1)
                                     .append("path", 1)
                                     .append("tenantId", 1)
                                     .append("projectId", 1))
                             .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String oldPath = doc.getString("path");
                if (oldPath == null || !oldPath.startsWith(rewrite.from)) continue;
                String newPath = rewrite.to + oldPath.substring(rewrite.from.length());

                // Collision handling: another doc might already sit at
                // the target path for the same (tenant, project) —
                // typically because boot ran with the new code first
                // and re-created the canonical row before the legacy
                // one was retired. Drop the legacy entry rather than
                // hitting the unique index. Bootstraps are
                // content-deterministic so deletion is safe.
                boolean collides = mongoTemplate.getCollection("documents").countDocuments(
                        Filters.and(
                                Filters.eq("tenantId", doc.getString("tenantId")),
                                Filters.eq("projectId", doc.getString("projectId")),
                                Filters.eq("path", newPath))) > 0;
                if (collides) {
                    mongoTemplate.getCollection("documents").deleteOne(
                            Filters.eq("_id", doc.get("_id")));
                    droppedDuplicates++;
                    continue;
                }

                UpdateResult res = mongoTemplate.getCollection("documents").updateOne(
                        Filters.eq("_id", doc.get("_id")),
                        new Document("$set", new Document("path", newPath)));
                if (res.getModifiedCount() > 0) updated++;
            }
        }
        if (droppedDuplicates > 0) {
            log.info("System-path-prefix migration: dropped {} duplicate legacy entry/ies for {} → {}",
                    droppedDuplicates, rewrite.from, rewrite.to);
        }
        return updated + droppedDuplicates;
    }

    private record PrefixRewrite(String from, String to) {}
}
