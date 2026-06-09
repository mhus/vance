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
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link FookUpstreamService}. All
 * collaborators are mocked.
 */
class FookUpstreamServiceTest {

    private FookTicketService ticketService;
    private FookTicketAnonymizer anonymizer;
    private InboxItemService inboxItemService;
    private SettingService settingService;
    private ClusterMasterService masterService;
    private TicketProvider provider;
    private FookUpstreamService service;

    @BeforeEach
    void setUp() {
        ticketService = mock(FookTicketService.class);
        anonymizer = new FookTicketAnonymizer();   // real — pure logic
        inboxItemService = mock(InboxItemService.class);
        settingService = mock(SettingService.class);
        masterService = mock(ClusterMasterService.class);
        when(masterService.isLocalPodMaster()).thenReturn(true);
        provider = mock(TicketProvider.class);
        when(provider.name()).thenReturn("github");

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
                masterService, List.of(provider));
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
                .url("https://github.com/mhus/vance/issues/4287")
                .build());

        service.sendTick();

        verify(provider).create(any(ProviderTicketDraft.class));
        verify(ticketService).markTransferred("uuid-1", "github", "4287",
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
                .provider("github").externalId("1").url("u").build());

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

        verify(ticketService, never()).markTransferred(any(), any(), any(), any());
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
                .provider("github").externalId("1").url("u").build());

        service.sendTick();

        verify(ticketService).markTransferred(any(), any(), any(), any());
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
    void poll_tick_mirrors_state_change_and_posts_status_inbox() {
        TicketDocument transferred = transferredTicket("uuid-1",
                "github", "4287", "open", null);
        when(ticketService.listTransferredForPolling()).thenReturn(List.of(transferred));
        when(provider.pollUpdates(any(), any())).thenReturn(List.of(
                ProviderTicketUpdate.builder()
                        .ref(ProviderTicketRef.builder()
                                .provider("github")
                                .externalId("4287")
                                .url("https://github.com/mhus/vance/issues/4287")
                                .build())
                        .state("closed")
                        .updatedAt(Instant.now())
                        .newComments(List.of())
                        .build()));

        service.pollTick();

        verify(ticketService).markUpstreamState("uuid-1", "closed");

        ArgumentCaptor<InboxItemDocument> cap =
                ArgumentCaptor.forClass(InboxItemDocument.class);
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

        ArgumentCaptor<InboxItemDocument> cap =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService, times(1)).create(cap.capture());
        InboxItemDocument item = cap.getValue();
        assertThat(item.getTitle()).contains("ford");
        assertThat(item.getBody()).contains("Can you reproduce");
        assertThat(item.getTags()).contains("fook-comment");
        assertThat(item.isRequiresAction()).isTrue();
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
}
