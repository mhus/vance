package de.mhus.vance.brain.kit.provisioning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which projects the periodic check reaches, and from which pod. */
class KitProvisioningCheckTickTest {

    private KitProvisioningProperties properties;
    private KitProvisioningCheck check;
    private ProjectService projectService;
    private TenantService tenantService;
    private ClusterMasterService masterService;
    private KitProvisioningCheckTick tick;

    @BeforeEach
    void setUp() {
        properties = new KitProvisioningProperties();
        check = mock(KitProvisioningCheck.class);
        projectService = mock(ProjectService.class);
        tenantService = mock(TenantService.class);
        masterService = mock(ClusterMasterService.class);
        tick = new KitProvisioningCheckTick(
                properties, check, projectService, tenantService, masterService);
        when(check.check(any(), any()))
                .thenReturn(new KitProvisioningCheck.Report(List.of(), List.of(), List.of()));
    }

    private static TenantDocument tenant(String name) {
        TenantDocument doc = new TenantDocument();
        doc.setName(name);
        return doc;
    }

    private static ProjectDocument project(
            String name, String homeNode, ProjectStatus status) {
        ProjectDocument doc = new ProjectDocument();
        doc.setTenantId("acme");
        doc.setName(name);
        doc.setHomeNode(homeNode);
        doc.setStatus(status);
        return doc;
    }

    @Test
    void withoutTheMasterLease_doesNothing() {
        when(masterService.isLocalPodMaster()).thenReturn(false);

        tick.tick();

        verify(tenantService, never()).all();
        verify(check, never()).check(any(), any());
    }

    @Test
    void disabled_doesNothingEvenAsMaster() {
        properties.setCheckEnabled(false);
        when(masterService.isLocalPodMaster()).thenReturn(true);

        tick.tick();

        verify(check, never()).check(any(), any());
    }

    @Test
    void projectStrandedOnADeadPod_isStillChecked() {
        // The bug this replaced: findByHomeNode(self) never returns this one, so
        // the check never reached the most common state of an EPHEMERAL project
        // after a restart. Ownership is not a precondition for provisioning.
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("test1", "nyota-samar", ProjectStatus.RUNNING)));

        tick.tick();

        verify(check).check("acme", "test1");
    }

    @Test
    void projectWithoutAnyOwner_isChecked() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("unclaimed", null, ProjectStatus.SUSPENDED)));

        tick.tick();

        verify(check).check("acme", "unclaimed");
    }

    @Test
    void closedProject_isSkipped() {
        // Nothing to provision into, and it would be swept forever.
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("gone", null, ProjectStatus.CLOSED)));

        tick.tick();

        verify(check, never()).check(any(), any());
    }

    @Test
    void everyTenantIsSwept() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme"), tenant("globex")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("a", null, ProjectStatus.RUNNING)));
        when(projectService.all("globex")).thenReturn(List.of(
                project("b", null, ProjectStatus.RUNNING)));

        tick.tick();

        verify(check).check("acme", "a");
        verify(check).check("acme", "b");
    }

    @Test
    void oneFailingProjectDoesNotEndTheSweep() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme")));
        when(projectService.all("acme")).thenReturn(List.of(
                project("broken", null, ProjectStatus.RUNNING),
                project("fine", null, ProjectStatus.RUNNING)));
        when(check.check("acme", "broken")).thenThrow(new IllegalStateException("boom"));

        tick.tick();

        verify(check).check("acme", "fine");
    }
}
