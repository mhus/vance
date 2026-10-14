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
 * The sweeper round: idle reap and dead-pod reap feed the
 * {@link LeaseRevocationDispatcher}, live pods are never reaped, and a
 * failed round is contained. The routing matrix itself is
 * {@link LeaseRevocationDispatcherTest}'s subject.
 */
class ResourceLeaseSweeperTest {

    private static final String SELF = "pod-self";

    private ResourceLeaseService leaseService;
    private LeaseRevocationDispatcher dispatcher;
    private ClusterService cluster;
    private ResourceLeaseSweeper sweeper;

    @BeforeEach
    void setUp() {
        leaseService = mock(ResourceLeaseService.class);
        dispatcher = mock(LeaseRevocationDispatcher.class);
        cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        when(leaseService.reapIdle(any())).thenAnswer(inv -> List.of());
        when(leaseService.reapPod(any())).thenAnswer(inv -> List.of());
        when(leaseService.reconcile(any())).thenReturn(0L);
        when(leaseService.listHolders()).thenReturn(List.of());
        sweeper = new ResourceLeaseSweeper(leaseService, dispatcher, cluster);
        ReflectionTestUtils.setField(sweeper, "reconcileSafetyMs", 60_000L);
    }

    private static ResourceLeaseHolderDocument holder(String pod) {
        ResourceLeaseHolderDocument doc = new ResourceLeaseHolderDocument();
        doc.setToken("rl-1");
        doc.setType("test");
        doc.setResource("tex2pdf");
        doc.setHolderRef("ref");
        doc.setTenantId("acme");
        doc.setPod(pod);
        doc.setPayload(Map.of("jobId", "j-42"));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private List<LeaseHolderInfo> dispatched() {
        ArgumentCaptor<List<LeaseHolderInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(dispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    void sweep_idleReapedHolders_goToTheDispatcher() {
        when(leaseService.reapIdle(null)).thenReturn(List.of(holder(SELF)));

        sweeper.sweepRound();

        assertThat(dispatched())
                .singleElement()
                .satisfies(info -> assertThat(info.payload()).containsEntry("jobId", "j-42"));
    }

    @Test
    void sweep_deadPodIsReaped_entirePod() {
        ResourceLeaseHolderDocument doc = holder("pod-gone");
        when(leaseService.listHolders()).thenReturn(List.of(doc));
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());
        when(leaseService.reapPod("pod-gone")).thenReturn(List.of(doc));

        sweeper.sweepRound();

        verify(leaseService).reapPod("pod-gone");
        assertThat(dispatched()).extracting(LeaseHolderInfo::token).containsExactly("rl-1");
    }

    @Test
    void sweep_livePods_areNotReaped() {
        when(leaseService.listHolders()).thenReturn(List.of(holder(SELF), holder("pod-other")));
        when(cluster.resolveEndpointByPodId("pod-other")).thenReturn(Optional.of("http://other"));

        sweeper.sweepRound();

        verify(leaseService, never()).reapPod(any());
    }

    @Test
    void sweep_failedRound_isContained() {
        when(leaseService.reapIdle(null)).thenThrow(new IllegalStateException("mongo down"));

        sweeper.sweep();

        // Reaching this point means the exception was contained.
        verify(leaseService, never()).reapPod(any());
    }
}
