package de.mhus.vance.brain.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code podBound} dispatch matrix of the sweeper (§8 of
 * {@code planning/lease-system.md}): who cleans up a reaped lease when
 * the holder pod is alive / unreachable / dead, split by whether the
 * resource dies with the pod.
 */
class ResourceLeaseSweeperTest {

    private static final String SELF = "pod-self";
    private static final String OTHER = "pod-other";

    private ResourceLeaseService leaseService;
    private LeaseHandlerRegistry registry;
    private ClusterService cluster;
    private HttpLeaseRevokedClient revokedClient;
    private ResourceLeaseSweeper sweeper;

    @BeforeEach
    void setUp() {
        leaseService = mock(ResourceLeaseService.class);
        registry = mock(LeaseHandlerRegistry.class);
        cluster = mock(ClusterService.class);
        revokedClient = mock(HttpLeaseRevokedClient.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        when(leaseService.reapIdle(any())).thenAnswer(inv -> List.of());
        when(leaseService.reapPod(any())).thenAnswer(inv -> List.of());
        when(leaseService.reconcile(any())).thenReturn(0L);
        when(leaseService.listHolders()).thenReturn(List.of());
        when(leaseService.toInfo(any())).thenAnswer(inv -> {
            ResourceLeaseHolderDocument doc = inv.getArgument(0);
            return new LeaseHolderInfo(
                    doc.getToken(),
                    doc.getType(),
                    doc.getResource(),
                    doc.getHolderRef(),
                    doc.getTenantId(),
                    doc.getProjectId(),
                    doc.getUserId(),
                    doc.getPod(),
                    doc.isPodBound(),
                    doc.getPayload() == null ? Map.of() : doc.getPayload());
        });
        sweeper = new ResourceLeaseSweeper(leaseService, registry, cluster, revokedClient);
        ReflectionTestUtils.setField(sweeper, "reconcileSafetyMs", 60_000L);
    }

    private ResourceLeaseHolderDocument holder(String pod, boolean podBound) {
        ResourceLeaseHolderDocument doc = new ResourceLeaseHolderDocument();
        doc.setToken("rl-1");
        doc.setType("test");
        doc.setResource("tex2pdf");
        doc.setHolderRef("ref");
        doc.setTenantId("acme");
        doc.setPod(pod);
        doc.setPodBound(podBound);
        doc.setPayload(Map.of("jobId", "j-42"));
        return doc;
    }

    private LeaseHolderInfo capturedDispatch() {
        ArgumentCaptor<LeaseHolderInfo> captor = ArgumentCaptor.forClass(LeaseHolderInfo.class);
        verify(registry).dispatch(captor.capture());
        return captor.getValue();
    }

    @Test
    void dispatch_holderOnThisPod_runsHandlerLocally() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder(SELF, false)));
        when(cluster.resolveEndpointByPodId(SELF)).thenReturn(Optional.of("http://self"));

        sweeper.sweepRound();

        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
        verify(revokedClient, never()).post(any(), any());
    }

    @Test
    void dispatch_holderOnAliveForeignPod_forwardsViaInternalPost() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder(OTHER, false)));
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));

        sweeper.sweepRound();

        verify(revokedClient).post(org.mockito.ArgumentMatchers.eq("http://other"), any());
        verify(registry, never()).dispatch(any());
    }

    @Test
    void dispatch_forwardFails_podBoundResource_noLocalCleanup() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder(OTHER, true)));
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unreachable"))
                .when(revokedClient)
                .post(any(), any());

        sweeper.sweepRound();

        verify(registry, never()).dispatch(any());
    }

    @Test
    void dispatch_forwardFails_notPodBound_reaperCleansUpLocally() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder(OTHER, false)));
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unreachable"))
                .when(revokedClient)
                .post(any(), any());

        sweeper.sweepRound();

        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
    }

    @Test
    void dispatch_deadPod_podBoundResource_diedWithThePod() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder("pod-gone", true)));
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());

        sweeper.sweepRound();

        verify(registry, never()).dispatch(any());
        verify(revokedClient, never()).post(any(), any());
    }

    @Test
    void dispatch_deadPod_notPodBound_reaperCleansUpWithPayload() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder("pod-gone", false)));
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());

        sweeper.sweepRound();

        LeaseHolderInfo info = capturedDispatch();
        assertThat(info.token()).isEqualTo("rl-1");
        assertThat(info.payload()).containsEntry("jobId", "j-42");
    }

    @Test
    void sweep_deadPodIsReaped_entirePod() {
        ResourceLeaseHolderDocument doc = holder("pod-gone", false);
        when(leaseService.listHolders()).thenReturn(List.of(doc));
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());
        when(leaseService.reapPod("pod-gone")).thenReturn(List.of(doc));

        sweeper.sweepRound();

        verify(leaseService).reapPod("pod-gone");
        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
    }

    @Test
    void sweep_failedRound_isContained() {
        when(leaseService.reapIdle(null)).thenThrow(new IllegalStateException("mongo down"));

        sweeper.sweep();

        // Reaching this point means the exception was contained.
        verify(leaseService, never()).reapPod(any());
    }
}
