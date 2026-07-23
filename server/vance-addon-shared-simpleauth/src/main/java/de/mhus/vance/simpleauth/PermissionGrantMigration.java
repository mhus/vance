package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time backfill: turn the organisational {@code ProjectDocument.teamIds}
 * association ("this team works on this project") into concrete
 * {@code PROJECT × TEAM × WRITER} grants, so existing users don't lose access
 * the moment enforcement goes live.
 *
 * <p>Idempotent + marker-guarded: runs once (a marker document in
 * {@code _permission_migrations} pins it), and {@link PermissionGrantService#set}
 * overwrites rather than duplicates. Runs only when this addon is loaded (it is
 * a bean of the addon). Guarding via the marker — not just {@code set}'s
 * idempotency — matters so that a later admin edit (e.g. removing a team grant)
 * is not re-seeded on the next boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionGrantMigration implements ApplicationRunner {

    static final String MARKER_COLLECTION = "_permission_migrations";
    static final String MARKER_ID = "teamIds-to-grants-v1";
    private static final String CREATED_BY = "teamIds-migration";

    private final MongoTemplate mongo;
    private final TenantService tenantService;
    private final ProjectService projectService;
    private final PermissionGrantService grants;

    @Override
    public void run(ApplicationArguments args) {
        if (alreadyRan()) {
            return;
        }
        int seeded = 0;
        for (TenantDocument tenant : tenantService.all()) {
            String tenantId = tenant.getName();
            for (ProjectDocument project : projectService.all(tenantId)) {
                // SYSTEM/podless projects are covered by R4/R7 — never grant them.
                if (ProjectService.isPodless(project.getName()) || project.getTeamIds() == null) {
                    continue;
                }
                for (String team : project.getTeamIds()) {
                    if (team == null || team.isBlank()) {
                        continue;
                    }
                    grants.set(tenantId, GrantScopeType.PROJECT, project.getName(),
                            GrantSubjectType.TEAM, team, GrantRole.WRITER, CREATED_BY);
                    seeded++;
                }
            }
        }
        markRan();
        log.info("PermissionGrantMigration: seeded {} team-WRITER grants from teamIds", seeded);
    }

    private boolean alreadyRan() {
        return mongo.getCollection(MARKER_COLLECTION)
                .countDocuments(new org.bson.Document("_id", MARKER_ID)) > 0;
    }

    private void markRan() {
        try {
            mongo.getCollection(MARKER_COLLECTION).insertOne(new org.bson.Document("_id", MARKER_ID));
        } catch (DuplicateKeyException | com.mongodb.MongoWriteException e) {
            // Another pod won the race — fine, the work is idempotent.
            log.debug("PermissionGrantMigration marker already present: {}", e.toString());
        }
    }
}
