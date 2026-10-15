package de.mhus.vance.brain.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code podBound} dispatch matrix of {@link LeaseRevocationDispatcher}
 * (§6 of {@code specification/public/lease-system.md}): who cleans up a
 * reaped lease when the holder pod is alive / unreachable / dead, split by
 * whether the resource dies with the pod — for the sweeper's synchronous
 * path and the lazy reap's asynchronous one.
 */
class LeaseRevocationDispatcherTest {

    private static final String SELF = "pod-self";
    private static final String OTHER = "pod-other";

    private LeaseHandlerRegistry registry;
    private ClusterService cluster;
    private HttpLeaseRevokedClient revokedClient;
    private LeaseRevocationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = mock(LeaseHandlerRegistry.class);
        cluster = mock(ClusterService.class);
        revokedClient = mock(HttpLeaseRevokedClient.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        dispatcher = new LeaseRevocationDispatcher(registry, cluster, revokedClient);
    }

    private static LeaseHolderInfo holder(String pod, boolean podBound) {
        return new LeaseHolderInfo(
                "rl-1", "test", "tex2pdf", "ref", "acme", null, null, pod, podBound, Map.of("jobId", "j-42"));
    }

    private LeaseHolderInfo capturedDispatch() {
        ArgumentCaptor<LeaseHolderInfo> captor = ArgumentCaptor.forClass(LeaseHolderInfo.class);
        verify(registry).dispatch(captor.capture());
        return captor.getValue();
    }

    @Test
    void dispatch_holderOnThisPod_runsHandlerLocally() {
        when(cluster.resolveEndpointByPodId(SELF)).thenReturn(Optional.of("http://self"));

        dispatcher.dispatch(List.of(holder(SELF, false)));

        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
        verify(revokedClient, never()).post(any(), any());
    }

    @Test
    void dispatch_holderOnThisPod_withoutOwnEndpoint_stillRunsHandlerLocally() {
        // This pod is alive by definition — a lagging heartbeat row must not
        // turn a pod-bound resource running here into "died with the pod".
        when(cluster.resolveEndpointByPodId(SELF)).thenReturn(Optional.empty());

        dispatcher.dispatch(List.of(holder(SELF, true)));

        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
        verify(revokedClient, never()).post(any(), any());
    }

    @Test
    void dispatch_holderOnAliveForeignPod_forwardsViaInternalPost() {
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));

        dispatcher.dispatch(List.of(holder(OTHER, false)));

        verify(revokedClient).post(org.mockito.ArgumentMatchers.eq("http://other"), any());
        verify(registry, never()).dispatch(any());
    }

    @Test
    void dispatch_forwardFails_podBoundResource_noLocalCleanup() {
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unreachable"))
                .when(revokedClient)
                .post(any(), any());

        dispatcher.dispatch(List.of(holder(OTHER, true)));

        verify(registry, never()).dispatch(any());
    }

    @Test
    void dispatch_forwardFails_notPodBound_reaperCleansUpLocally() {
        when(cluster.resolveEndpointByPodId(OTHER)).thenReturn(Optional.of("http://other"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unreachable"))
                .when(revokedClient)
                .post(any(), any());

        dispatcher.dispatch(List.of(holder(OTHER, false)));

        assertThat(capturedDispatch().token()).isEqualTo("rl-1");
    }

    @Test
    void dispatch_deadPod_podBoundResource_diedWithThePod() {
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());

        dispatcher.dispatch(List.of(holder("pod-gone", true)));

        verify(registry, never()).dispatch(any());
        verify(revokedClient, never()).post(any(), any());
    }

    @Test
    void dispatch_deadPod_notPodBound_reaperCleansUpWithPayload() {
        when(cluster.resolveEndpointByPodId("pod-gone")).thenReturn(Optional.empty());

        dispatcher.dispatch(List.of(holder("pod-gone", false)));

        LeaseHolderInfo info = capturedDispatch();
        assertThat(info.token()).isEqualTo("rl-1");
        assertThat(info.payload()).containsEntry("jobId", "j-42");
    }

    @Test
    void onReaped_lazyReapPath_dispatchesOffTheCallingThread() {
        when(cluster.resolveEndpointByPodId(SELF)).thenReturn(Optional.of("http://self"));

        dispatcher.onReaped(List.of(holder(SELF, false)));

        verify(registry, org.mockito.Mockito.timeout(2_000)).dispatch(any());
    }
}
