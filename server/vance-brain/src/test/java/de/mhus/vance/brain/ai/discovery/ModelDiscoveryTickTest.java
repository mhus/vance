package de.mhus.vance.brain.ai.discovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tick's whole value is what it does NOT let happen: non-master
 * pods do nothing (one writer in the cluster), and one tenant's broken
 * endpoint does not stop the other tenants from being discovered.
 */
class ModelDiscoveryTickTest {

    private final ClusterMasterService masterService = mock(ClusterMasterService.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final ModelDiscoveryService discoveryService = mock(ModelDiscoveryService.class);

    private final ModelDiscoveryTick tick = new ModelDiscoveryTick(masterService, tenantService, discoveryService);

    @Test
    void nonMasterPod_doesNothing() {
        when(masterService.isLocalPodMaster()).thenReturn(false);

        tick.tick();

        verify(discoveryService, never()).discoverForTenant(any());
    }

    @Test
    void masterRunsDiscovery_forEveryTenant() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("acme"), tenant("globex")));

        tick.tick();

        verify(discoveryService).discoverForTenant("acme");
        verify(discoveryService).discoverForTenant("globex");
    }

    @Test
    void oneTenantFailure_doesNotStopTheOthers() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(tenantService.all()).thenReturn(List.of(tenant("broken"), tenant("acme")));
        when(discoveryService.discoverForTenant("broken"))
                .thenThrow(new IllegalStateException("listing endpoint down"));

        tick.tick();

        // The broken tenant failed, but acme was still discovered.
        verify(discoveryService).discoverForTenant("acme");
    }

    @Test
    void blankTenantName_isSkippedNotFailed() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        TenantDocument nameless = new TenantDocument();
        when(tenantService.all()).thenReturn(List.of(nameless));

        tick.tick();

        verify(discoveryService, never()).discoverForTenant(any());
    }

    private static TenantDocument tenant(String name) {
        TenantDocument doc = new TenantDocument();
        doc.setName(name);
        return doc;
    }
}
