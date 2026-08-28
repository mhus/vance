package de.mhus.vance.brain.fook.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.brain.fook.FookTicketService;
import de.mhus.vance.brain.fook.TicketContext;
import de.mhus.vance.brain.fook.TicketDocument;
import de.mhus.vance.brain.fook.TicketRelations;
import de.mhus.vance.brain.fook.TicketReporter;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link FookUpstreamService}. All
 * collaborators are mocked.
 */
class FookUpstreamServiceTest {

    private FookTicketService ticketService;
    private FookTicketAnonymizer anonymizer;
    private MaximegalonService inboxItemService;
    private SettingService settingService;
    private ClusterMasterService masterService;
    private TicketProvider provider;
    private FookUpstreamService service;

    @BeforeEach
    void setUp() {
        ticketService = mock(FookTicketService.class);
        anonymizer = new FookTicketAnonymizer();   // real — pure logic
        inboxItemService = mock(MaximegalonService.class);
        settingService = mock(SettingService.class);
        masterService = mock(ClusterMasterService.class);
        when(masterService.isLocalPodMaster()).thenReturn(true);
        provider = mock(TicketProvider.class);
        when(provider.name()).thenReturn("github");
        // A Mockito mock does not inherit an interface's default method — it
        // answers false. Real beans get `true` from TicketProvider itself, so
        // without this every poll test would exercise the "cannot poll" exit.
        when(provider.supportsPolling()).thenReturn(true);
        // Same reason: an unstubbed int-returning default method answers 0,
        // which the service clamps to a batch of one. Uncapped unless a test
        // says otherwise.
        when(provider.pollBatchSize()).thenReturn(Integer.MAX_VALUE);

        // Sensible defaults — individual tests override.
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_MODE))).thenReturn("automatic");
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_PROVIDER_TYPE))).thenReturn("github");
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_FINGERPRINT))).thenReturn("fp-abc");
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_INSTANCE_SECRET))).thenReturn("secret");
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_SCRUB_PATTERNS))).thenReturn("email");
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_EXTRA_LABELS))).thenReturn("");
        when(settingService.getBooleanValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_ANONYMIZE), anyBoolean())).thenReturn(true);
        when(settingService.getBooleanValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_POLL_ENABLED), anyBoolean())).thenReturn(true);

        service = new FookUpstreamService(
                ticketService, anonymizer, inboxItemService, settingService,
                masterProvider(masterService), List.of(provider));
    }

    // ─── multi-pod guard ────────────────────────────────────────────

    @Test
    void send_tick_skips_on_non_master_pod() {
        when(masterService.isLocalPodMaster()).thenReturn(false);
        service.sendTick();
        // Nothing — not even setting reads — should happen on a follower.
        verifyNoInteractions(ticketService);
        verifyNoInteractions(provider);
    }

    @Test
    void poll_tick_skips_on_non_master_pod() {
        when(masterService.isLocalPodMaster()).thenReturn(false);
        service.pollTick();
        verifyNoInteractions(ticketService);
        verifyNoInteractions(provider);
    }

    // ─── send tick: gating ──────────────────────────────────────────

    @Test
    void send_tick_short_circuits_when_mode_is_never() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_MODE))).thenReturn("never");
        service.sendTick();
        verifyNoInteractions(ticketService);
        verify(provider, never()).create(any());
    }

    @Test
    void send_tick_short_circuits_when_provider_type_has_no_bean() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_PROVIDER_TYPE))).thenReturn("gitlab");
        service.sendTick();
        verifyNoInteractions(ticketService);
    }

    @Test
    void send_tick_does_nothing_when_no_pending_tickets() {
        when(ticketService.listPendingTransport()).thenReturn(List.of());
        service.sendTick();
        verify(provider, never()).create(any());
    }

    // ─── send tick: happy path ──────────────────────────────────────

    @Test
    void send_tick_transfers_ticket_and_updates_inbox_item() {
        TicketDocument ticket = ticket("uuid-1", "inbox-99");
        when(ticketService.listPendingTransport()).thenReturn(List.of(ticket));
        when(provider.create(any())).thenReturn(ProviderTicketRef.builder()
                .provider("github")
                .externalId("4287")
                .displayId("4287")
                .url("https://github.com/mhus/vance/issues/4287")
                .build());

        service.sendTick();

        verify(provider).create(any(ProviderTicketDraft.class));
        verify(ticketService).markTransferred("uuid-1", "github", "4287", "4287",
                "https://github.com/mhus/vance/issues/4287");

        ArgumentCaptor<String> inboxIdCap = ArgumentCaptor.forClass(String.class);
        verify(inboxItemService).updateContent(
                eq("acme"), inboxIdCap.capture(), any(), any(), any(), eq("fook"));
        assertThat(inboxIdCap.getValue()).isEqualTo("inbox-99");
    }

    @Test
    void send_tick_passes_anonymized_draft_to_provider() {
        TicketDocument ticket = ticket("uuid-1", "inbox-99");
        when(ticketService.listPendingTransport()).thenReturn(List.of(ticket));
        when(provider.create(any())).thenReturn(ProviderTicketRef.builder()
                .provider("github").externalId("1").displayId("1").url("u").build());

        service.sendTick();

        ArgumentCaptor<ProviderTicketDraft> cap =
                ArgumentCaptor.forClass(ProviderTicketDraft.class);
        verify(provider).create(cap.capture());
        ProviderTicketDraft draft = cap.getValue();
        // The reporter's actual userId/tenantId must not leak through.
        assertThat(draft.getBody()).doesNotContain("alice").doesNotContain("acme");
        assertThat(draft.getReporterHash()).matches("[0-9a-f]{16}");
        assertThat(draft.getInstanceFingerprint()).isEqualTo("fp-abc");
        assertThat(draft.getFookTicketId()).isEqualTo("uuid-1");
    }

    // ─── send tick: failure paths ───────────────────────────────────

    @Test
    void retryable_provider_failure_leaves_ticket_pending() {
        TicketDocument ticket = ticket("uuid-1", "inbox-99");
        when(ticketService.listPendingTransport()).thenReturn(List.of(ticket));
        when(provider.create(any())).thenThrow(
                new ProviderException("rate-limited", true));

        service.sendTick();

        verify(ticketService, never()).markTransferred(any(), any(), any(), any(), any());
        verify(ticketService, never()).markTransferFailed(any(), any());
        verifyNoInteractions(inboxItemService);
    }

    @Test
    void permanent_provider_failure_marks_failed_and_updates_inbox() {
        TicketDocument ticket = ticket("uuid-1", "inbox-99");
        when(ticketService.listPendingTransport()).thenReturn(List.of(ticket));
        when(provider.create(any())).thenThrow(
                new ProviderException("Bad credentials", false));

        service.sendTick();

        verify(ticketService).markTransferFailed("uuid-1", "Bad credentials");
        verify(inboxItemService).updateContent(
                eq("acme"), eq("inbox-99"),
                eq("Ticket transfer failed"),
                org.mockito.ArgumentMatchers.contains("failed permanently"),
                any(), eq("fook"));
    }

    @Test
    void no_inbox_update_when_ticket_lacks_inbox_item_id() {
        TicketDocument ticket = ticket("uuid-1", /* no inbox id */ null);
        when(ticketService.listPendingTransport()).thenReturn(List.of(ticket));
        when(provider.create(any())).thenReturn(ProviderTicketRef.builder()
                .provider("github").externalId("1").displayId("1").url("u").build());

        service.sendTick();

        verify(ticketService).markTransferred(any(), any(), any(), any(), any());
        verify(inboxItemService, never()).updateContent(any(), any(), any(), any(), any(), any());
    }

    // ─── poll tick ──────────────────────────────────────────────────

    @Test
    void poll_tick_short_circuits_when_mode_never() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_MODE))).thenReturn("never");
        service.pollTick();
        verifyNoInteractions(ticketService);
    }

    @Test
    void poll_tick_short_circuits_when_disabled() {
        when(settingService.getBooleanValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_POLL_ENABLED), anyBoolean()))
                .thenReturn(false);
        service.pollTick();
        verifyNoInteractions(ticketService);
    }

    @Test
    void poll_tick_short_circuits_when_provider_cannot_poll() {
        when(provider.supportsPolling()).thenReturn(false);
        service.pollTick();
        // Not even the ticket listing: the adapter said it has no way to
        // ask, so there is nothing to walk.
        verifyNoInteractions(ticketService);
        verify(provider, never()).pollUpdates(any(), any());
    }

    @Test
    void send_tick_stops_pass_when_provider_reports_rate_limit() {
        TicketDocument first = ticket("uuid-1", "inbox-1");
        TicketDocument second = ticket("uuid-2", "inbox-2");
        when(ticketService.listPendingTransport()).thenReturn(List.of(first, second));
        when(provider.create(any())).thenThrow(
                ProviderException.rateLimited("HTTP 429", java.time.Duration.ofMinutes(1)));

        service.sendTick();

        // One attempt, not two: the second ticket would hit the same limit,
        // and against a one-per-minute cap the wasted refusals also cost
        // throughput.
        verify(provider, times(1)).create(any());
        verify(ticketService, never()).markTransferFailed(any(), any());
    }

    // ─── poll tick: batching rotates ────────────────────────────────

    /**
     * The finding: an adapter that pays a request per ticket caps the batch,
     * and a cap on an unordered list is a permanent blind spot — the same
     * head is polled every tick and everything behind it is never asked
     * about again. Nothing in the old code moved the window.
     */
    @Test
    void poll_tick_polls_the_least_recently_checked_first() {
        when(provider.pollBatchSize()).thenReturn(2);
        Instant now = Instant.now();
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(
                transferredTicket("fresh", "github", "1", "open", now),
                transferredTicket("stale", "github", "2", "open", now.minusSeconds(7200)),
                transferredTicket("never", "github", "3", "open", null),
                transferredTicket("middle", "github", "4", "open", now.minusSeconds(600))));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of());

        service.pollTick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProviderTicketRef>> refs =
                ArgumentCaptor.forClass(List.class);
        verify(provider).pollUpdates(refs.capture(), any());
        // Never-asked first, then the oldest timestamp — and only two, which
        // is what the adapter said it could afford.
        assertThat(refs.getValue()).hasSize(2);
        assertThat(refs.getValue().get(0).getExternalId()).isEqualTo("3");
        assertThat(refs.getValue().get(1).getExternalId()).isEqualTo("2");
    }

    /**
     * The half that makes the order actually rotate: a ticket with nothing
     * new never reports a change, so if only the ones that answered were
     * stamped it would stay the stalest ticket for ever and the window would
     * never advance past it.
     */
    @Test
    void poll_tick_stamps_even_the_tickets_that_had_nothing_to_say() {
        when(provider.pollBatchSize()).thenReturn(1);
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(
                transferredTicket("quiet", "github", "9", "open", null)));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of());

        service.pollTick();

        verify(ticketService).markUpstreamSynced("quiet");
    }

    @Test
    void poll_tick_mirrors_state_change_and_posts_status_inbox() {
        TicketDocument transferred = transferredTicket("uuid-1",
                "github", "4287", "open", null);
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(transferred));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of(
                ProviderTicketUpdate.builder()
                        .ref(ProviderTicketRef.builder()
                                .provider("github")
                                .externalId("4287")
                                .displayId("4287")
                                .url("https://github.com/mhus/vance/issues/4287")
                                .build())
                        .state("closed")
                        .updatedAt(Instant.now())
                        .newComments(List.of())
                        .build()));

        service.pollTick();

        verify(ticketService).markUpstreamState("uuid-1", "closed");

        ArgumentCaptor<MaximegalonDocument> cap =
                ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService).create(cap.capture());
        assertThat(cap.getValue().getTitle()).contains("closed");
        assertThat(cap.getValue().getTags()).contains("fook-status");
        assertThat(cap.getValue().isRequiresAction()).isFalse();
    }

    @Test
    void poll_tick_creates_feedback_inbox_per_new_comment() {
        TicketDocument transferred = transferredTicket("uuid-1",
                "github", "4287", "open", null);
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(transferred));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of(
                ProviderTicketUpdate.builder()
                        .ref(ProviderTicketRef.builder()
                                .provider("github")
                                .externalId("4287")
                                .displayId("4287")
                                .url("u")
                                .build())
                        .state("open")
                        .newComments(List.of(
                                ProviderTicketUpdate.ProviderComment.builder()
                                        .externalId("c1")
                                        .author("ford")
                                        .body("Can you reproduce on 1.4-rc1?")
                                        .createdAt(Instant.now())
                                        .build()))
                        .build()));

        service.pollTick();

        ArgumentCaptor<MaximegalonDocument> cap =
                ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(inboxItemService, times(1)).create(cap.capture());
        MaximegalonDocument item = cap.getValue();
        assertThat(item.getTitle()).contains("ford");
        assertThat(item.getBody()).contains("Can you reproduce");
        assertThat(item.getTags()).contains("fook-comment");
        assertThat(item.isRequiresAction()).isTrue();
        // The delivered comment is recorded so the next tick can dedup it.
        verify(ticketService).recordSyncedComments("uuid-1", List.of("c1"));
    }

    @Test
    void poll_tick_does_not_re_deliver_already_synced_comment() {
        // Regression (code-review-2): the provider is queried with a global
        // `since`, so an already-delivered comment can be re-fetched. A ticket
        // that already recorded comment c1 must NOT re-post it.
        TicketDocument transferred = TicketDocument.builder()
                .id("uuid-1").title("T").type("bug").severity("medium")
                .status("transferred").transportApproval("auto").inboxItemId("inbox-x")
                .createdAt(Instant.now().minusSeconds(3600))
                .transferredAt(Instant.now().minusSeconds(1800))
                .upstreamProvider("github").upstreamExternalId("4287")
                .upstreamUrl("https://example/4287").upstreamState("open")
                .syncedCommentExternalIds(List.of("c1"))
                .description("d")
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE).userId("alice").tenantId("acme").build())
                .relations(TicketRelations.builder()
                        .rootCauseOf(List.of()).relatedTo(List.of()).build())
                .build();
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(transferred));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of(
                ProviderTicketUpdate.builder()
                        .ref(ProviderTicketRef.builder()
                                .provider("github").externalId("4287").displayId("4287").url("u").build())
                        .state("open")
                        .newComments(List.of(
                                ProviderTicketUpdate.ProviderComment.builder()
                                        .externalId("c1").author("ford").body("dup")
                                        .createdAt(Instant.now()).build()))
                        .build()));

        service.pollTick();

        // c1 was already delivered → no new FEEDBACK inbox item.
        verify(inboxItemService, times(0)).create(any());
    }

    @Test
    void poll_tick_filters_tickets_by_matching_provider() {
        // Ticket was transferred by 'github' but service current provider
        // is 'gitlab' — should NOT be polled.
        TicketDocument transferred = transferredTicket(
                "uuid-1", "github", "4287", "open", null);
        when(provider.name()).thenReturn("gitlab");
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(transferred));

        when(settingService.getStringValueCascade(any(), any(), any(),
                eq(FookUpstreamService.SETTING_PROVIDER_TYPE))).thenReturn("gitlab");

        service.pollTick();

        verify(provider, never()).pollUpdates(any(), any());
    }

    // ─── helpers ────────────────────────────────────────────────────

    private TicketDocument ticket(String uuid, @org.jspecify.annotations.Nullable String inboxId) {
        return TicketDocument.builder()
                .id(uuid)
                .title("Brain crash on boot")
                .type("bug")
                .severity("high")
                .status("new")
                .transportApproval("auto")
                .inboxItemId(inboxId)
                .createdAt(Instant.now())
                .description("Boot throws NPE in recipes loader.")
                .triageNote(null)
                .context(TicketContext.builder()
                        .projectId("web-redesign")
                        .sessionId("sess-1")
                        .processId("proc-1")
                        .recipe("arthur")
                        .engine("arthur")
                        .build())
                .relations(TicketRelations.builder()
                        .duplicateOf(null)
                        .rootCauseOf(List.of())
                        .relatedTo(List.of())
                        .build())
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE)
                        .userId("alice")
                        .tenantId("acme")
                        .build())
                .build();
    }

    private TicketDocument transferredTicket(
            String uuid, String provider, String externalId,
            String state, @org.jspecify.annotations.Nullable Instant lastSyncedAt) {
        return TicketDocument.builder()
                .id(uuid)
                .title("T")
                .type("bug")
                .severity("medium")
                .status("transferred")
                .transportApproval("auto")
                .inboxItemId("inbox-x")
                .createdAt(Instant.now().minusSeconds(3600))
                .transferredAt(Instant.now().minusSeconds(1800))
                .upstreamProvider(provider)
                .upstreamExternalId(externalId)
                .upstreamUrl("https://example/" + externalId)
                .upstreamState(state)
                .upstreamLastSyncedAt(lastSyncedAt)
                .syncedCommentExternalIds(List.of())
                .description("d")
                .triageNote(null)
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE)
                        .userId("alice")
                        .tenantId("acme")
                        .build())
                .relations(TicketRelations.builder()
                        .duplicateOf(null)
                        .rootCauseOf(List.of())
                        .relatedTo(List.of())
                        .build())
                .build();
    }
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ClusterMasterService> masterProvider(
            @org.jspecify.annotations.Nullable ClusterMasterService service) {
        ObjectProvider<ClusterMasterService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    void send_tick_skips_when_the_cluster_master_feature_is_off() {
        // No coordinator means nobody may write to the foreign tracker: a
        // duplicate issue there cannot be taken back. Opposite call to
        // MagratheaWatchdogScanner, which sweeps internally and idempotently.
        FookUpstreamService withoutMaster = new FookUpstreamService(
                ticketService, anonymizer, inboxItemService, settingService,
                masterProvider(null), List.of(provider));

        withoutMaster.sendTick();
        withoutMaster.pollTick();

        verifyNoInteractions(ticketService);
        verifyNoInteractions(provider);
    }
}
