package de.mhus.vance.brain.cluster.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When a push goes out. Demand is a level and a push needs an edge, so the
 * whole subject here is change-detection plus a heartbeat.
 */
class PlacementDemandNotifierTest {

    private PlacementDemandService demandService;
    private PlacementWebhookClient webhookClient;
    private ClusterProperties properties;
    private PlacementDemandNotifier notifier;

    @BeforeEach
    void setUp() {
        demandService = mock(PlacementDemandService.class);
        webhookClient = mock(PlacementWebhookClient.class);
        properties = new ClusterProperties();
        lenient().when(webhookClient.isConfigured()).thenReturn(true);
        lenient().when(webhookClient.isPerTenant()).thenReturn(false);
        lenient().when(webhookClient.send(any(), any()))
                .thenReturn(new PlacementWebhookClient.Result(true, 202, ""));
        notifier = new PlacementDemandNotifier(
                properties, demandService, webhookClient,
                new MetricService(new SimpleMeterRegistry()));
    }

    private static PlacementDemand demand(Instant at, PlacementDemand.Entry... entries) {
        return new PlacementDemand("default", at, List.of(entries));
    }

    private static PlacementDemand.Entry entry(String tenant, int count, int score) {
        return new PlacementDemand.Entry(
                tenant, Map.of("gpu", "true"), PlacementGap.NO_ELIGIBLE_POD,
                count, score, Instant.parse("2026-08-26T09:00:00Z"));
    }

    @Test
    void noWebhookConfigured_neverSends() {
        when(webhookClient.isConfigured()).thenReturn(false);

        notifier.notifyRound();

        verify(demandService, never()).currentDemand();
        verify(webhookClient, never()).send(any(), any());
    }

    @Test
    void firstNonEmptyDemand_isSent() {
        when(demandService.currentDemand())
                .thenReturn(demand(Instant.now(), entry("acme", 2, 40)));

        notifier.notifyRound();

        verify(webhookClient).send(any(), eq(null));
    }

    @Test
    void unchangedDemand_isNotResentBeforeTheHeartbeat() {
        PlacementDemand same = demand(Instant.now(), entry("acme", 2, 40));
        when(demandService.currentDemand()).thenReturn(same);

        notifier.notifyRound();
        notifier.notifyRound();
        notifier.notifyRound();

        verify(webhookClient, times(1)).send(any(), any());
    }

    @Test
    void changedDemand_isSentAgain() {
        when(demandService.currentDemand())
                .thenReturn(demand(Instant.now(), entry("acme", 2, 40)),
                        demand(Instant.now(), entry("acme", 3, 60)));

        notifier.notifyRound();
        notifier.notifyRound();

        verify(webhookClient, times(2)).send(any(), any());
    }

    @Test
    void aMovingTimestampAloneIsNotAChange() {
        // sentAt and oldestSince advance on their own. If they were part of the
        // fingerprint, every tick would be a delivery and change-detection would
        // be pointless.
        when(demandService.currentDemand())
                .thenReturn(demand(Instant.parse("2026-08-26T10:00:00Z"), entry("acme", 2, 40)),
                        demand(Instant.parse("2026-08-26T10:01:00Z"), entry("acme", 2, 40)));

        notifier.notifyRound();
        notifier.notifyRound();

        verify(webhookClient, times(1)).send(any(), any());
    }

    @Test
    void heartbeatResendsAfterTheInterval() {
        properties.getPlacement().setWebhookResendInterval(Duration.ZERO);
        PlacementDemand same = demand(Instant.now(), entry("acme", 2, 40));
        when(demandService.currentDemand()).thenReturn(same);

        notifier.notifyRound();
        notifier.notifyRound();

        verify(webhookClient, times(2)).send(any(), any());
    }

    @Test
    void demandDroppingToEmpty_sendsOnceMore() {
        when(demandService.currentDemand())
                .thenReturn(demand(Instant.now(), entry("acme", 2, 40)),
                        demand(Instant.now()),
                        demand(Instant.now()));

        notifier.notifyRound();
        notifier.notifyRound();
        notifier.notifyRound();

        // Without the empty send, the receiver cannot tell "still needed" from
        // "handled" except by timeout — and then it builds one pod too many.
        verify(webhookClient, times(2)).send(any(), any());
    }

    @Test
    void emptyDemandFromTheStart_isNotAnnounced() {
        when(demandService.currentDemand()).thenReturn(demand(Instant.now()));

        notifier.notifyRound();

        verify(webhookClient, never()).send(any(), any());
    }

    @Test
    void aFailedSendIsRetriedByTheNextRound() {
        when(webhookClient.send(any(), any()))
                .thenReturn(new PlacementWebhookClient.Result(false, 503, "busy"),
                        new PlacementWebhookClient.Result(true, 202, ""));
        PlacementDemand same = demand(Instant.now(), entry("acme", 2, 40));
        when(demandService.currentDemand()).thenReturn(same);

        notifier.notifyRound();
        notifier.notifyRound();

        // Nothing was remembered from the failed attempt, so the unchanged
        // demand still counts as unsent — that is the retry, with no queue.
        verify(webhookClient, times(2)).send(any(), any());
    }

    @Test
    void perTenantUrl_sendsOncePerTenant() {
        when(webhookClient.isPerTenant()).thenReturn(true);
        when(demandService.currentDemand()).thenReturn(demand(
                Instant.now(), entry("acme", 1, 10), entry("globex", 1, 10)));

        notifier.notifyRound();

        verify(webhookClient).send(any(), eq("acme"));
        verify(webhookClient).send(any(), eq("globex"));
    }

    @Test
    void perTenant_tenantThatFallsToZero_stillGetsItsEmptySend() {
        when(webhookClient.isPerTenant()).thenReturn(true);
        when(demandService.currentDemand()).thenReturn(
                demand(Instant.now(), entry("acme", 1, 10)),
                demand(Instant.now()));

        notifier.notifyRound();
        notifier.notifyRound();

        // The second round has no entry for acme at all — it has to come from
        // what was last sent, or the receiver waits forever.
        verify(webhookClient, times(2)).send(any(), eq("acme"));
    }

    @Test
    void perTenant_bodyCarriesOnlyThatTenantsGroups() {
        when(webhookClient.isPerTenant()).thenReturn(true);
        when(demandService.currentDemand()).thenReturn(demand(
                Instant.now(), entry("acme", 1, 10), entry("globex", 1, 10)));

        notifier.notifyRound();

        org.mockito.ArgumentCaptor<PlacementDemand> captor =
                org.mockito.ArgumentCaptor.forClass(PlacementDemand.class);
        verify(webhookClient).send(captor.capture(), eq("acme"));
        assertThat(captor.getValue().demand())
                .extracting(PlacementDemand.Entry::tenantId)
                .containsExactly("acme");
    }
}
