package de.mhus.vance.simpleauth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * teamIds → PROJECT×TEAM×WRITER backfill: seeds each project team once,
 * skips podless/system projects, and is marker-guarded (permission-system
 * Phase D).
 */
class PermissionGrantMigrationTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final PermissionGrantService grants = mock(PermissionGrantService.class);
    private final PermissionGrantMigration migration =
            new PermissionGrantMigration(mongo, tenantService, projectService, grants);

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> markerCollection(long alreadyPresent) {
        MongoCollection<Document> coll = mock(MongoCollection.class);
        when(coll.countDocuments(any(Bson.class))).thenReturn(alreadyPresent);
        when(mongo.getCollection(PermissionGrantMigration.MARKER_COLLECTION)).thenReturn(coll);
        return coll;
    }

    @Test
    void seeds_team_writer_grants_and_skips_podless() {
        markerCollection(0);
        when(tenantService.all()).thenReturn(List.of(tenant("acme")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("proj", List.of("rd", "qa")),
                project("_user_alice", List.of("rd")),   // podless → skip
                project("nogroups", null)));               // no teamIds → skip

        migration.run(null);

        verify(grants).set("acme", GrantScopeType.PROJECT, "proj",
                GrantSubjectType.TEAM, "rd", GrantRole.WRITER, "teamIds-migration");
        verify(grants).set("acme", GrantScopeType.PROJECT, "proj",
                GrantSubjectType.TEAM, "qa", GrantRole.WRITER, "teamIds-migration");
        verify(grants, never()).set(any(), any(), eq("_user_alice"), any(), any(), any(), any());
        verify(grants, never()).set(any(), any(), eq("nogroups"), any(), any(), any(), any());
    }

    @Test
    void marker_present_skips_the_whole_run() {
        markerCollection(1);

        migration.run(null);

        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
        verify(tenantService, never()).all();
    }

    private static TenantDocument tenant(String name) {
        TenantDocument t = new TenantDocument();
        t.setName(name);
        return t;
    }

    private static ProjectDocument project(String name, List<String> teamIds) {
        ProjectDocument p = new ProjectDocument();
        p.setName(name);
        p.setTeamIds(teamIds);
        return p;
    }
}
