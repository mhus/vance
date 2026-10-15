package de.mhus.vance.brain.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.insights.LeaseCounterInsightsDto;
import de.mhus.vance.api.insights.LeaseHolderInsightsDto;
import de.mhus.vance.api.insights.LeaseInsightsDto;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.lease.LeaseCounter;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.ResourceLeaseCounterDocument;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The operator surface: revoke runs the handler dispatch like a reap,
 * the tenant snapshot maps holders and counters with pod liveness and
 * the booked reference value.
 */
class LeaseAdminServiceTest {

    private static final String SELF = "pod-self";
    private static final Instant AT = Instant.parse("2026-10-15T08:00:00Z");

    private ResourceLeaseService leaseService;
    private LeaseRevocationDispatcher dispatcher;
    private ResourceLeaseSweeper sweeper;
    private ClusterService cluster;
    private LeaseAdminService service;

    @BeforeEach
    void setUp() {
        leaseService = mock(ResourceLeaseService.class);
        dispatcher = mock(LeaseRevocationDispatcher.class);
        sweeper = mock(ResourceLeaseSweeper.class);
        cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        when(cluster.resolveEndpointByPodId(any())).thenReturn(Optional.empty());
        when(cluster.listCluster()).thenReturn(List.of());
        service = new LeaseAdminService(leaseService, dispatcher, sweeper, cluster);
    }

    private static ResourceLeaseHolderDocument holder(String token, String pod, String... levels) {
        return ResourceLeaseHolderDocument.builder()
                .token(token)
                .state("HELD")
                .type("tex")
                .resource("tex2pdf")
                .holderRef("ref-" + token)
                .tenantId("acme")
                .projectId("proj-1")
                .userId("alice")
                .pod(pod)
                .podBound(true)
                .payload(Map.of("jobId", 42))
                .levels(new ArrayList<>(List.of(levels)))
                .ttlSeconds(120)
                .acquiredAt(AT)
                .lastSeenAt(AT)
                .build();
    }

    @Test
    void revoke_dispatchesTheRevokedHolder() {
        when(leaseService.revoke("rl-1", "acme")).thenReturn(Optional.of(holder("rl-1", SELF)));

        Optional<LeaseHolderInfo> revoked = service.revoke("rl-1", "acme");

        assertThat(revoked).map(LeaseHolderInfo::token).contains("rl-1");
        verify(dispatcher).dispatch(List.of(revoked.orElseThrow()));
    }

    @Test
    void revoke_nothingRevoked_dispatchesNothing() {
        when(leaseService.revoke("rl-1", "acme")).thenReturn(Optional.empty());

        assertThat(service.revoke("rl-1", "acme")).isEmpty();
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void revokePod_dispatchesEveryHolderOfThePod() {
        when(leaseService.reapPod("pod-x")).thenReturn(List.of(holder("rl-1", "pod-x"), holder("rl-2", "pod-x")));

        List<LeaseHolderInfo> revoked = service.revokePod("pod-x");

        assertThat(revoked).extracting(LeaseHolderInfo::token).containsExactly("rl-1", "rl-2");
        verify(dispatcher).dispatch(revoked);
    }

    @Test
    void snapshot_mapsHoldersWithPodLivenessAndNodeName() {
        when(leaseService.listHolders("acme")).thenReturn(List.of(holder("rl-1", SELF), holder("rl-2", "pod-gone")));
        when(leaseService.listHolders()).thenReturn(List.of());
        when(leaseService.listCounters("acme")).thenReturn(List.of());
        BrainPodDocument self =
                BrainPodDocument.builder().podId(SELF).nodeName("brain-0").build();
        when(cluster.listCluster()).thenReturn(List.of(self));

        LeaseInsightsDto dto = service.snapshot("acme");

        assertThat(dto.getHolders())
                .extracting(LeaseHolderInsightsDto::getToken)
                .containsExactly("rl-1", "rl-2");
        LeaseHolderInsightsDto live = dto.getHolders().get(0);
        assertThat(live.isPodAlive()).isTrue();
        assertThat(live.getPodNodeName()).isEqualTo("brain-0");
        assertThat(live.getPayload()).containsEntry("jobId", "42");
        LeaseHolderInsightsDto dead = dto.getHolders().get(1);
        assertThat(dead.isPodAlive()).isFalse();
        assertThat(dead.getPodNodeName()).isNull();
    }

    @Test
    void snapshot_countersCarryScopeWithoutTenantAndTheBookedReference() {
        when(leaseService.listHolders("acme")).thenReturn(List.of());
        when(leaseService.listHolders())
                .thenReturn(List.of(
                        holder("rl-1", SELF, "tex2pdf|project|acme/proj-1", "tex2pdf|tenant|acme"),
                        holder("rl-2", SELF, "tex2pdf|tenant|acme")));
        when(leaseService.listCounters("acme"))
                .thenReturn(List.of(
                        LeaseCounter.of(new ResourceLeaseCounterDocument("tex2pdf|tenant|acme", 3, AT)),
                        LeaseCounter.of(new ResourceLeaseCounterDocument("tex2pdf|project|acme/proj-1", 1, AT))));

        LeaseInsightsDto dto = service.snapshot("acme");

        assertThat(dto.getCounters())
                .extracting(
                        LeaseCounterInsightsDto::getLevel,
                        LeaseCounterInsightsDto::getScope,
                        LeaseCounterInsightsDto::getCount,
                        LeaseCounterInsightsDto::getBooked)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("project", "proj-1", 1L, 1L),
                        // Drift: three booked on the counter, two holders know about it.
                        org.assertj.core.groups.Tuple.tuple("tenant", "acme", 3L, 2L));
    }

    @Test
    void sweep_runsOneReaperRound() {
        service.sweep();

        verify(sweeper).sweepRound();
    }
}
