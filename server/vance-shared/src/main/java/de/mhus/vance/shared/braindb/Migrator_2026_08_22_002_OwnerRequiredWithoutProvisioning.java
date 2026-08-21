package de.mhus.vance.shared.braindb;

import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Drops kit provisioning as a reason to keep a project on a pod.
 *
 * <p>{@link Migrator_2026_08_22_001_OwnerRequired} counted four activation
 * sources, matching the four listeners on {@code ProjectEnginesStartRequested}.
 * That was the wrong test. Schedulers, hooks and event triggers arm in-memory
 * state that has to stay armed; kit provisioning runs once when the project
 * comes up and is then finished. Since kits are the ordinary way to set a
 * project up, the extra prefix pinned nearly every project in an installation
 * to a pod forever — the exact opposite of what the capacity model is for.
 *
 * <p>Only clears, never sets: a project whose flag is true is re-tested against
 * the three remaining prefixes and released if none of them match. Projects
 * that were already false are untouched, and setting is left to the live
 * derivation. That keeps this crash-safe and idempotent — a partial run leaves
 * some projects still pinned, and re-running finishes the job.
 *
 * <p>The prefixes are literals for the same reason as in the previous
 * migration: a migration reproduces a state as of its date and must not change
 * meaning when a constant is refactored later.
 */
public final class Migrator_2026_08_22_002_OwnerRequiredWithoutProvisioning
        implements SchemaMigration {

    /** Snapshot of the activation-source paths as of 2026-08-22, provisioning removed. */
    private static final List<String> ACTIVATION_PREFIXES = List.of(
            "_vance/scheduler/",
            "_vance/hooks/",
            "_vance/events/");

    @Override
    public void up(SchemaMigrationContext context) {
        MongoTemplate mongo = context.mongoTemplate();
        int released = 0;

        // Bounded by the projects currently pinned, not by the installation:
        // the whole point of the fix is that this set is small.
        List<Document> pinned = new ArrayList<>();
        mongo.getCollection("projects")
                .find(new Document("ownerRequired", true))
                .projection(new Document("tenantId", 1).append("name", 1))
                .into(pinned);

        for (Document project : pinned) {
            String tenantId = project.getString("tenantId");
            String name = project.getString("name");
            if (tenantId == null || name == null) continue;
            if (hasBackgroundWork(mongo, tenantId, name)) continue;
            mongo.updateFirst(
                    new Query(Criteria.where("tenantId").is(tenantId).and("name").is(name)),
                    new Update().set("ownerRequired", false),
                    "projects");
            released++;
        }

        System.getLogger(
                        Migrator_2026_08_22_002_OwnerRequiredWithoutProvisioning.class.getName())
                .log(System.Logger.Level.INFO,
                        "released " + released + " of " + pinned.size()
                                + " pinned project(s) that only had kit provisioning");
    }

    private static boolean hasBackgroundWork(
            MongoTemplate mongo, String tenantId, String projectId) {
        List<Document> orClauses = new ArrayList<>();
        for (String prefix : ACTIVATION_PREFIXES) {
            orClauses.add(new Document("path",
                    new Document("$regex", "^" + Pattern.quote(prefix))));
        }
        Document filter = new Document("tenantId", tenantId)
                .append("projectId", projectId)
                .append("status", "ACTIVE")
                .append("$or", orClauses);
        return mongo.getCollection("documents").find(filter).limit(1).first() != null;
    }
}
