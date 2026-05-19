package de.mhus.vance.brain.bootstrap;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectKind;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * One-shot startup migration that renames the tenant-wide system
 * project from {@code _vance} to {@code _tenant} — the
 * {@link HomeBootstrapService#TENANT_PROJECT_NAME} rename of 2026-05-19.
 *
 * <p>What it does, in order:
 *
 * <ol>
 *   <li>For each {@code SYSTEM}-kind document in the {@code projects}
 *       collection with {@code name="_vance"}: rename to {@code "_tenant"}.</li>
 *   <li>Across every Mongo collection in the database: rewrite
 *       {@code projectId="_vance" → "_tenant"} in bulk.</li>
 * </ol>
 *
 * <p>Idempotent: if no {@code _vance} system project is found, nothing
 * is done — subsequent boots are no-ops. A marker is intentionally
 * <i>not</i> stored separately; the absence of {@code _vance} rows is
 * the marker.
 *
 * <p>Runs in {@code @PostConstruct} so it executes before
 * {@link BootstrapBrainService} and any login-time call to
 * {@link HomeBootstrapService#ensureTenantProject}; both would otherwise
 * create a fresh {@code _tenant} project alongside the legacy {@code _vance}
 * row and collide on the unique {@code (tenantId, name)} index when the
 * migration then tried to rename.
 *
 * <p>Scope-limit: the migration only touches the <b>project</b> named
 * {@code _vance}. The <b>tenant</b> named {@code _vance} (system tenant,
 * {@code TenantService.SYSTEM_TENANT}) is a different identifier and
 * stays untouched. The document-path prefix {@code _vance/} (inside
 * paths like {@code _vance/scheduler/foo.yaml}) is also untouched —
 * that's a historical namespace for system-managed documents, no
 * collision with the renamed project.
 *
 * <p>Note: {@code BrainPodDocument.activeProjects} carries
 * {@code "tenant/project"} strings and could in theory hold
 * {@code "<tenant>/_vance"} entries. They are transient runtime
 * state that the pod refreshes on its own lifecycle hook, so the
 * migration intentionally does not rewrite them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VanceToTenantProjectMigration {

    /** Pre-rename name of the project we're migrating away from. */
    private static final String LEGACY_PROJECT_NAME = "_vance";

    /** Post-rename name — must stay in sync with {@link HomeBootstrapService#TENANT_PROJECT_NAME}. */
    private static final String TARGET_PROJECT_NAME = HomeBootstrapService.TENANT_PROJECT_NAME;

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void migrate() {
        // 1. Find SYSTEM-kind projects named _vance. If none — already migrated, exit.
        Query findLegacy = Query.query(Criteria.where("name").is(LEGACY_PROJECT_NAME)
                .and("kind").is(ProjectKind.SYSTEM.name()));
        long legacyCount = mongoTemplate.count(findLegacy, "projects");
        if (legacyCount == 0) {
            log.debug("Tenant-project rename migration: no legacy '_vance' SYSTEM project — nothing to do");
            return;
        }

        log.info("Tenant-project rename migration: found {} legacy '_vance' SYSTEM project(s), migrating to '_tenant'",
                legacyCount);

        // 2. Rename the projects themselves.
        UpdateResult renamed = mongoTemplate.updateMulti(
                findLegacy,
                Update.update("name", TARGET_PROJECT_NAME),
                "projects");
        log.info("Tenant-project rename migration: renamed {} project document(s)", renamed.getModifiedCount());

        // 3. Rewrite projectId across every collection in the DB.
        // Iterate generically so future entities are automatically covered.
        long totalDocs = 0;
        for (String collection : mongoTemplate.getCollectionNames()) {
            if ("projects".equals(collection)) {
                continue; // already handled
            }
            UpdateResult res = mongoTemplate.getCollection(collection).updateMany(
                    new Document("projectId", LEGACY_PROJECT_NAME),
                    new Document("$set", new Document("projectId", TARGET_PROJECT_NAME)));
            long modified = res.getModifiedCount();
            if (modified > 0) {
                log.info("Tenant-project rename migration: collection='{}' updated {} doc(s) projectId _vance → _tenant",
                        collection, modified);
                totalDocs += modified;
            }
        }
        log.info("Tenant-project rename migration: complete — {} project(s) renamed, "
                + "{} cross-collection projectId references rewritten",
                renamed.getModifiedCount(), totalDocs);
    }
}
