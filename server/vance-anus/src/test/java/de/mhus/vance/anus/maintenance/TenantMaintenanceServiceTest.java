package de.mhus.vance.anus.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import de.mhus.vance.anus.cluster.ProjectClusterService;
import de.mhus.vance.anus.cluster.ProjectClusterService.DrainDecision;
import de.mhus.vance.anus.cluster.ProjectClusterService.DrainVerdict;
import de.mhus.vance.anus.maintenance.MaintenanceReport.EntityResult;
import de.mhus.vance.anus.maintenance.MaintenanceReport.Operation;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class TenantMaintenanceServiceTest {

    private static final String T = "748498812";

    private final TenantService tenants = mock(TenantService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final UserService users = mock(UserService.class);
    private final ProjectMaintenanceService projectMaintenance = mock(ProjectMaintenanceService.class);
    private final UserMaintenanceService userMaintenance = mock(UserMaintenanceService.class);
    private final ProjectClusterService cluster = mock(ProjectClusterService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ProjectDataHandler handler = mock(ProjectDataHandler.class);

    private TenantMaintenanceService service;

    private static TenantDocument tenant(String name, boolean enabled) {
        return TenantDocument.builder().name(name).enabled(enabled).build();
    }

    private static ProjectDocument project(String name, ProjectKind kind) {
        ProjectDocument doc = new ProjectDocument();
        doc.setName(name);
        doc.setKind(kind);
        return doc;
    }

    private static UserDocument user(String name) {
        UserDocument doc = new UserDocument();
        doc.setName(name);
        return doc;
    }

    private static MaintenanceReport report(boolean complete) {
        return new MaintenanceReport(
                T, "x", Operation.DELETE, List.of(EntityResult.of("h", Set.of("c"), 3)), List.of(), complete);
    }

    @BeforeEach
    void setUp() {
        service = new TenantMaintenanceService(
                tenants, projects, users, projectMaintenance, userMaintenance, cluster, mongo);
        when(tenants.findByName(T)).thenReturn(Optional.of(tenant(T, false)));
        when(projects.all(T)).thenReturn(List.of());
        when(users.all(T)).thenReturn(List.of());
        when(mongo.getCollectionName(any(Class.class)))
                .thenAnswer(call -> ((Class<?>) call.getArgument(0)).getSimpleName());
        when(mongo.getCollectionNames()).thenReturn(Set.of());
        when(mongo.remove(any(Query.class), anyString())).thenReturn(DeleteResult.acknowledged(1));
        when(projectMaintenance.handlers()).thenReturn(List.of(handler));
        when(handler.id()).thenReturn("h");
        when(handler.collections()).thenReturn(Set.of("c"));
        when(cluster.drainBefore(anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new DrainDecision(DrainVerdict.RELEASED, true, null));
    }

    // ─── guards

    @Test
    void theSystemTenantAndUnderscoreNamesAreRefusedBeforeAnythingIsTouched() {
        when(tenants.findByName("_vance")).thenReturn(Optional.of(tenant("_vance", false)));
        when(tenants.findByName("_other")).thenReturn(Optional.of(tenant("_other", false)));

        assertThatThrownBy(() -> service.delete("_vance", false))
                .isInstanceOf(TenantService.TenantProtectedException.class);
        assertThatThrownBy(() -> service.delete("_other", false))
                .isInstanceOf(TenantService.TenantProtectedException.class);

        verify(projects, never()).all(anyString());
        verify(mongo, never()).remove(any(Query.class), anyString());
    }

    @Test
    void anEnabledTenantIsRefusedBeforeAnythingIsTouched() {
        when(tenants.findByName(T)).thenReturn(Optional.of(tenant(T, true)));

        assertThatThrownBy(() -> service.delete(T, false))
                .isInstanceOf(TenantService.TenantStillEnabledException.class);

        verify(projectMaintenance, never()).delete(anyString(), anyString(), anyBoolean());
        verify(mongo, never()).remove(any(Query.class), anyString());
    }

    @Test
    void anUnknownTenantIsNotFound() {
        when(tenants.findByName("999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("999999999", false))
                .isInstanceOf(TenantService.TenantNotFoundException.class);
    }

    // ─── the sequence

    @Test
    void projectsThenAccountsThenTheSweepThenTheTenantRowLast() {
        when(projects.all(T))
                .thenReturn(List.of(
                        project("alpha", ProjectKind.NORMAL),
                        project("_tenant", ProjectKind.SYSTEM),
                        project("_user_admin", ProjectKind.SYSTEM)));
        when(users.all(T)).thenReturn(List.of(user("admin")));
        when(projectMaintenance.delete(T, "alpha", false)).thenReturn(report(true));
        when(userMaintenance.delete(T, "admin", false)).thenReturn(report(true));

        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isTrue();
        InOrder order = inOrder(cluster, projectMaintenance, userMaintenance, handler, mongo, tenants);
        order.verify(cluster).drainBefore(T, "alpha", false, false);
        order.verify(projectMaintenance).delete(T, "alpha", false);
        order.verify(userMaintenance).delete(T, "admin", false);
        order.verify(handler, org.mockito.Mockito.atLeastOnce()).delete(T, "_tenant");
        order.verify(mongo, org.mockito.Mockito.atLeastOnce()).remove(any(Query.class), anyString());
        order.verify(tenants).delete(T);
        // A hub is the account's; a SYSTEM project is never deleted as a project.
        verify(projectMaintenance, never()).delete(T, "_tenant", false);
        verify(projectMaintenance, never()).delete(T, "_user_admin", false);
    }

    @Test
    void theSweepClearsEveryTenantKeyedCollectionOfExactlyThisTenant() {
        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isTrue();
        // The scan finds the documents of this module's classpath that carry a tenantId; settings and users are two.
        assertThat(service.keyedCollections()).contains("SettingDocument", "UserDocument", "ProjectDocument");
        verify(mongo).remove(any(Query.class), eq("SettingDocument"));
        verify(mongo).remove(any(Query.class), eq("UserDocument"));
        // Only the tenant's rows: the query is the tenantId and nothing wider.
        org.mockito.ArgumentCaptor<Query> query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce()).remove(query.capture(), eq("SettingDocument"));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("tenantId", T));
    }

    // ─── failure policy

    @Test
    void aProjectThatCannotBeDrainedIsSkippedAndTheTenantRowStays() {
        when(projects.all(T))
                .thenReturn(List.of(project("alpha", ProjectKind.NORMAL), project("beta", ProjectKind.NORMAL)));
        when(cluster.drainBefore(T, "alpha", false, false))
                .thenReturn(new DrainDecision(DrainVerdict.BLOCKED, true, null));
        when(projectMaintenance.delete(T, "beta", false)).thenReturn(report(true));

        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isFalse();
        verify(projectMaintenance, never()).delete(T, "alpha", false);
        // The rest of the run went on.
        verify(projectMaintenance).delete(T, "beta", false);
        verify(tenants, never()).delete(anyString());
        assertThat(result.entities()).anySatisfy(e -> {
            assertThat(e.handlerId()).isEqualTo("project:alpha");
            assertThat(e.note()).contains("FAILED");
        });
    }

    @Test
    void anAccountThatFailsKeepsTheTenantRowAndTheRunGoesOn() {
        when(users.all(T)).thenReturn(List.of(user("a"), user("b")));
        doThrow(new UserMaintenanceService.UserInUseException(List.of("owns a thing")))
                .when(userMaintenance)
                .delete(T, "a", false);
        when(userMaintenance.delete(T, "b", false)).thenReturn(report(true));

        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isFalse();
        verify(userMaintenance).delete(T, "b", false);
        verify(tenants, never()).delete(anyString());
    }

    @Test
    void aFailingSweepStepKeepsTheTenantRow() {
        when(mongo.remove(any(Query.class), eq("SettingDocument"))).thenThrow(new IllegalStateException("mongo down"));

        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isFalse();
        verify(tenants, never()).delete(anyString());
    }

    @Test
    void aCollectionNobodyClaimsThatStillHoldsTheTenantsRowsKeepsTheTenantRow() {
        when(mongo.getCollectionNames()).thenReturn(Set.of("orphan_things"));
        when(mongo.find(any(Query.class), eq(Document.class), eq("orphan_things")))
                .thenReturn(List.of(new Document("tenantId", T)));
        when(mongo.count(any(Query.class), eq("orphan_things"))).thenReturn(4L);

        MaintenanceReport result = service.delete(T, false);

        assertThat(result.complete()).isFalse();
        assertThat(result.unaccounted()).extracting(u -> u.collection()).containsExactly("orphan_things");
        verify(tenants, never()).delete(anyString());
        // Reported, never acted on: nothing was removed from it.
        verify(mongo, never()).remove(any(Query.class), eq("orphan_things"));
    }

    @Test
    void aSystemProjectsHandlerThatFailsKeepsTheTenantRow() {
        when(projects.all(T)).thenReturn(List.of(project("_tenant", ProjectKind.SYSTEM)));
        doThrow(new IllegalStateException("blob store down")).when(handler).delete(T, "_tenant");

        assertThat(service.delete(T, false).complete()).isFalse();
        verify(tenants, never()).delete(anyString());
    }

    @Test
    void forceReachesTheProjectAndAccountDeletes() {
        when(projects.all(T)).thenReturn(List.of(project("alpha", ProjectKind.NORMAL)));
        when(users.all(T)).thenReturn(List.of(user("a")));
        when(projectMaintenance.delete(T, "alpha", true)).thenReturn(report(true));
        when(userMaintenance.delete(T, "a", true)).thenReturn(report(true));

        assertThat(service.delete(T, true).complete()).isTrue();

        verify(cluster).drainBefore(T, "alpha", false, true);
        verify(projectMaintenance).delete(T, "alpha", true);
        verify(userMaintenance).delete(T, "a", true);
    }

    @Test
    void aRepeatedRunAfterAFailureFinishesTheJob() {
        when(mongo.remove(any(Query.class), eq("SettingDocument")))
                .thenThrow(new IllegalStateException("mongo down"))
                .thenReturn(DeleteResult.acknowledged(0));

        assertThat(service.delete(T, false).complete()).isFalse();
        assertThat(service.delete(T, false).complete()).isTrue();

        verify(tenants).delete(T);
    }

    // ─── inspect

    @Test
    void inspectCountsAndWritesNothing() {
        when(projects.all(T)).thenReturn(List.of(project("alpha", ProjectKind.NORMAL)));
        when(users.all(T)).thenReturn(List.of(user("a"), user("b")));
        when(mongo.count(any(Query.class), eq("SettingDocument"))).thenReturn(4L);

        MaintenanceReport report = service.inspect(T);

        assertThat(report.operation()).isEqualTo(Operation.INSPECT);
        assertThat(report.entities()).anySatisfy(e -> {
            assertThat(e.handlerId()).isEqualTo("projects");
            assertThat(e.affected()).isEqualTo(1);
        });
        assertThat(report.entities()).anySatisfy(e -> {
            assertThat(e.handlerId()).isEqualTo("users");
            assertThat(e.affected()).isEqualTo(2);
        });
        assertThat(report.entities()).anySatisfy(e -> {
            assertThat(e.handlerId()).isEqualTo("rows:SettingDocument");
            assertThat(e.affected()).isEqualTo(4);
        });
        verify(mongo, never()).remove(any(Query.class), anyString());
        verify(tenants, never()).delete(anyString());
    }

    @Test
    void inspectOfAnEnabledTenantIsAllowedBecauseItWritesNothing() {
        when(tenants.findByName(T)).thenReturn(Optional.of(tenant(T, true)));

        assertThat(service.inspect(T).complete()).isTrue();
    }
}
