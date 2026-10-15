package de.mhus.vance.anus.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.anus.lease.AnusLeaseService.CounterRow;
import de.mhus.vance.anus.lease.AnusLeaseService.HolderFilter;
import de.mhus.vance.anus.lease.AnusLeaseService.RevokeOutcome.Status;
import de.mhus.vance.shared.lease.LeaseCounter;
import de.mhus.vance.shared.lease.LeaseDefinitionLoader;
import de.mhus.vance.shared.lease.ResourceLeaseCounterDocument;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The brain/--force split of the revokes, the counter drift view and the
 * holder filter.
 */
class AnusLeaseServiceTest {

    private static final Instant AT = Instant.parse("2026-10-15T08:00:00Z");

    private ResourceLeaseService leaseService;
    private AnusBrainClient brainClient;
    private AnusLeaseService service;

    @BeforeEach
    void setUp() {
        leaseService = mock(ResourceLeaseService.class);
        brainClient = mock(AnusBrainClient.class);
        service = new AnusLeaseService(leaseService, mock(LeaseDefinitionLoader.class), brainClient);
    }

    private static ResourceLeaseHolderDocument holder(String token, String tenant, String pod, String... levels) {
        return ResourceLeaseHolderDocument.builder()
                .token(token)
                .type("tex")
                .resource("tex2pdf")
                .tenantId(tenant)
                .pod(pod)
                .levels(new ArrayList<>(List.of(levels)))
                .acquiredAt(AT)
                .build();
    }

    @Test
    void revoke_goesThroughTheBrain_andReadsTheRevokedTokens() {
        when(brainClient.internal("/internal/lease/admin/holders/rl-1/revoke", "POST", null))
                .thenReturn(new Response(200, "{\"revoked\":[{\"token\":\"rl-1\",\"type\":\"tex\"}]}"));

        var outcome = service.revoke("rl-1", false);

        assertThat(outcome.status()).isEqualTo(Status.REVOKED);
        assertThat(outcome.tokens()).containsExactly("rl-1");
        verify(leaseService, never()).revoke(anyString(), any());
    }

    @Test
    void revoke_brain404_isNotFound() {
        when(brainClient.internal(anyString(), eq("POST"), any())).thenReturn(new Response(404, ""));

        assertThat(service.revoke("rl-1", false).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void revoke_brainError_isFailedWithTheAnswer() {
        when(brainClient.internal(anyString(), eq("POST"), any())).thenReturn(new Response(500, "boom"));

        var outcome = service.revoke("rl-1", false);

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).isEqualTo("HTTP 500 boom");
    }

    @Test
    void revoke_force_staysInMongoWithoutTheBrain() {
        when(leaseService.revoke("rl-1", null)).thenReturn(Optional.of(holder("rl-1", "acme", "pod-1")));

        var outcome = service.revoke("rl-1", true);

        assertThat(outcome.status()).isEqualTo(Status.FORCED);
        assertThat(outcome.tokens()).containsExactly("rl-1");
        verify(brainClient, never()).internal(anyString(), anyString(), any());
    }

    @Test
    void revokePod_brainWithNoHolders_isNotFound() {
        when(brainClient.internal("/internal/lease/admin/pods/pod-1/revoke", "POST", null))
                .thenReturn(new Response(200, "{\"revoked\":[]}"));

        assertThat(service.revokePod("pod-1", false).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void revokePod_force_reapsThePodInMongo() {
        when(leaseService.reapPod("pod-1"))
                .thenReturn(List.of(holder("rl-1", "acme", "pod-1"), holder("rl-2", "acme", "pod-1")));

        var outcome = service.revokePod("pod-1", true);

        assertThat(outcome.status()).isEqualTo(Status.FORCED);
        assertThat(outcome.tokens()).containsExactly("rl-1", "rl-2");
    }

    @Test
    void counters_carryTheBookedReference_andMarkDrift() {
        when(leaseService.listHolders()).thenReturn(List.of(holder("rl-1", "acme", "pod-1", "tex2pdf|tenant|acme")));
        when(leaseService.listCounters())
                .thenReturn(List.of(
                        LeaseCounter.of(new ResourceLeaseCounterDocument("tex2pdf|tenant|acme", 1, AT)),
                        LeaseCounter.of(new ResourceLeaseCounterDocument("tex2pdf|global|", 2, AT))));

        List<CounterRow> rows = service.counters(null, null);

        assertThat(rows)
                .extracting(r -> r.counter().key(), CounterRow::booked, CounterRow::drifted)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("tex2pdf|global|", 0L, true),
                        org.assertj.core.groups.Tuple.tuple("tex2pdf|tenant|acme", 1L, false));
    }

    @Test
    void holders_filterCombines() {
        when(leaseService.listHolders())
                .thenReturn(List.of(
                        holder("rl-1", "acme", "pod-1"),
                        holder("rl-2", "acme", "pod-2"),
                        holder("rl-3", "other", "pod-1")));

        assertThat(service.holders(new HolderFilter("acme", null, "pod-1", null)))
                .extracting(ResourceLeaseHolderDocument::getToken)
                .containsExactly("rl-1");
    }
}
