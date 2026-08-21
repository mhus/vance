package de.mhus.vance.shared.braindb;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.schema.SchemaMigration;
import de.mhus.vance.shared.schema.SchemaMigrationContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Introduces the derived {@code ownerRequired} flag and retires
 * {@code lifecycleType=EPHEMERAL} as a default.
 *
 * <h2>Two steps, one reason</h2>
 * Whether a project has to be kept on a live pod used to be an operator flag
 * ({@code PERMANENT}) that no code path ever wrote — {@code setLifecycleType}
 * had no callers at all. So every project sat on the {@code EPHEMERAL} default,
 * both cluster recovery paths selected on {@code PERMANENT}, and neither ever
 * matched anything: schedulers, hooks and provisioning stopped after a restart
 * and nothing brought them back
 * ({@code planning/project-ownership-lease-design.md} §1.1).
 *
 * <ol>
 *   <li>{@code EPHEMERAL → AUTO}: nobody ever <em>chose</em> EPHEMERAL, it was
 *       what create() wrote. Leaving it would read as an explicit "never start
 *       this by itself" and keep the projects excluded. {@code AUTO} hands the
 *       decision to the derived flag; an operator who really wants the opt-out
 *       can now set it, which for the first time is possible.</li>
 *   <li>Backfill {@code ownerRequired} from the documents that decide it, so
 *       existing projects are correct before the first recovery tick runs
 *       rather than only after somebody edits a scheduler.</li>
 * </ol>
 *
 * <p><b>The prefixes are literals here, on purpose.</b> The running code reads
 * them from the loaders, two of which live in {@code vance-brain} and are not
 * visible from this module — but more importantly a migration reproduces a
 * state as of its date and must not silently change meaning when a constant is
 * refactored later.
 *
 * <p>Idempotent throughout: step 1 filters on the value it replaces, step 2
 * only fills rows that have no value yet, step 3 sets {@code true} which is
 * what it would set again.
 */
public final class Migrator_2026_08_22_001_OwnerRequired implements SchemaMigration {

    /** Snapshot of the activation-source paths as of 2026-08-22. */
    private static final List<String> ACTIVATION_PREFIXES = List.of(
            "_vance/scheduler/",
            "_vance/hooks/",
            "_vance/events/",
            "_vance/kits/provisioning.yaml");

    @Override
    public void up(SchemaMigrationContext context) {
        MongoTemplate mongo = context.mongoTemplate();
        System.Logger log =
                System.getLogger(Migrator_2026_08_22_001_OwnerRequired.class.getName());

        UpdateResult retyped = mongo.updateMulti(
                new Query(Criteria.where("lifecycleType").is("EPHEMERAL")),
                new Update().set("lifecycleType", "AUTO"),
                "projects");

        UpdateResult defaulted = mongo.updateMulti(
                new Query(Criteria.where("ownerRequired").exists(false)),
                new Update().set("ownerRequired", false),
                "projects");

        int flagged = 0;
        for (ProjectKey key : projectsWithBackgroundWork(mongo)) {
            UpdateResult r = mongo.updateFirst(
                    new Query(Criteria.where("tenantId").is(key.tenantId())
                            .and("name").is(key.projectId())),
                    new Update().set("ownerRequired", true),
                    "projects");
            flagged += (int) r.getMatchedCount();
        }

        log.log(System.Logger.Level.INFO,
                "lifecycleType EPHEMERAL→AUTO on " + retyped.getModifiedCount()
                        + " project(s); ownerRequired defaulted on " + defaulted.getModifiedCount()
                        + ", set on " + flagged + " project(s) carrying background work");
    }

    /**
     * The distinct {@code (tenantId, projectId)} pairs that own at least one
     * active document under an activation-source prefix.
     *
     * <p>One aggregation over {@code documents} rather than a per-project
     * probe: the project count is the thing that grows in a large
     * installation, and this has to stay independent of it.
     */
    private static Set<ProjectKey> projectsWithBackgroundWork(MongoTemplate mongo) {
        List<Document> orClauses = new ArrayList<>();
        for (String prefix : ACTIVATION_PREFIXES) {
            orClauses.add(new Document("path",
                    new Document("$regex", "^" + Pattern.quote(prefix))));
        }
        List<Document> pipeline = List.of(
                new Document("$match", new Document("status", "ACTIVE")
                        .append("$or", orClauses)),
                new Document("$group", new Document("_id",
                        new Document("tenantId", "$tenantId")
                                .append("projectId", "$projectId"))));

        Set<ProjectKey> keys = new LinkedHashSet<>();
        for (Document row : mongo.getCollection("documents").aggregate(pipeline)) {
            Document id = row.get("_id", Document.class);
            if (id == null) continue;
            String tenantId = id.getString("tenantId");
            String projectId = id.getString("projectId");
            if (tenantId == null || projectId == null) continue;
            keys.add(new ProjectKey(tenantId, projectId));
        }
        return keys;
    }

    private record ProjectKey(String tenantId, String projectId) {}
}
