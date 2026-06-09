package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link FookService}. The collaborators are
 * mocked; we drive {@link FookService#submit} → {@link
 * FookService#drainQueue} and assert the side-effects (ticket
 * service calls, inbox-item shapes, LightLlm request shape).
 *
 * <p>Single-text input model: reporters supply only {@code text},
 * Fook (the LLM) derives type / severity / title.
 */
class FookServiceTest {

    private FookTicketService ticketService;
    private LightLlmService lightLlm;
    private InboxItemService inboxItemService;
    private SettingService settingService;
    private FookService service;

    @BeforeEach
    void setUp() {
        ticketService = mock(FookTicketService.class);
        lightLlm = mock(LightLlmService.class);
        inboxItemService = mock(InboxItemService.class);
        settingService = mock(SettingService.class);
        // Default: upstream off — every existing test sees "never" so
        // their assertions stay valid. Tests that exercise the
        // transport-mode logic override this individually.
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq("fook.upstream.mode")))
                .thenReturn("never");
        when(inboxItemService.create(any())).thenAnswer(inv ->
                inv.<InboxItemDocument>getArgument(0));
        service = new FookService(
                ticketService, lightLlm, inboxItemService, settingService);

        when(ticketService.searchSimilar(any(), anyInt())).thenReturn(List.of());
    }

    // ─── submit + queue mechanics ───────────────────────────────────

    @Test
    void submit_returns_a_non_blank_id_and_counts_inflight() {
        String id = service.submit(engineSubmission("Brain crashed on boot."));
        assertThat(id).isNotBlank();
        assertThat(service.inFlight()).isEqualTo(1);
    }

    @Test
    void submit_rejects_null_request() {
        assertThatThrownBy(() -> service.submit(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_rejects_missing_reporter() {
        SubmissionRequest req = SubmissionRequest.builder()
                .text("Brain crashed.").build();
        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reporter");
    }

    @Test
    void submit_rejects_blank_text() {
        SubmissionRequest req = SubmissionRequest.builder()
                .text("   ")
                .reporter(reporterEngine())
                .build();
        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void drain_on_empty_queue_does_nothing() {
        service.drainQueue();
        verifyNoInteractions(lightLlm);
        verifyNoInteractions(inboxItemService);
    }

    // ─── new_ticket decision ────────────────────────────────────────

    @Test
    void new_ticket_decision_creates_ticket_and_inbox_item() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "new_ticket",
                        "derivedType", "bug",
                        "derivedSeverity", "high",
                        "derivedTitle", "Brain crash on boot",
                        "triageNote", "Looks novel.",
                        "reason", "Distinct symptom not seen before.",
                        "relatedTickets", List.of("uuid-related")));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission(
                "Brain crashes on boot — NPE in the recipes loader."));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> payload =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(payload.capture());
        assertThat(payload.getValue().getTitle()).isEqualTo("Brain crash on boot");
        assertThat(payload.getValue().getDescription())
                .isEqualTo("Brain crashes on boot — NPE in the recipes loader.");
        assertThat(payload.getValue().getType()).isEqualTo("bug");
        assertThat(payload.getValue().getSeverity()).isEqualTo("high");
        assertThat(payload.getValue().getTriageNote()).isEqualTo("Looks novel.");
        assertThat(payload.getValue().getRelatedTickets())
                .containsExactly("uuid-related");

        InboxItemDocument item = captureInbox();
        assertThat(item.getTenantId()).isEqualTo("acme");
        assertThat(item.getAssignedToUserId()).isEqualTo("alice");
        assertThat(item.getOriginatorUserId()).isEqualTo("fook");
        assertThat(item.getType()).isEqualTo(InboxItemType.OUTPUT_TEXT);
        assertThat(item.getCriticality()).isEqualTo(Criticality.LOW);
        assertThat(item.isRequiresAction()).isFalse();
        assertThat(item.getTags()).containsExactly("fook");
        assertThat(item.getPayload())
                .containsEntry("decision", "new_ticket")
                .containsEntry("ticketId", "uuid-new");
    }

    @Test
    void new_ticket_falls_back_when_llm_omits_derived_fields() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket", "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission(
                "First sentence is the fallback title.\nSecond sentence is body."));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> p =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(p.capture());
        assertThat(p.getValue().getTitle())
                .isEqualTo("First sentence is the fallback title.");
        assertThat(p.getValue().getType()).isEqualTo("other");
        assertThat(p.getValue().getSeverity()).isEqualTo("medium");
        // Description always carries the original raw text.
        assertThat(p.getValue().getDescription()).startsWith("First sentence");
    }

    // ─── merge_into decision ────────────────────────────────────────

    @Test
    void merge_into_calls_update_relations_and_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "merge_into",
                        "targetTicketId", "uuid-target",
                        "relation", "rootCauseOf",
                        "relatedTickets", List.of("uuid-extra-1"),
                        "reason", "Same defect, deeper cause."));

        service.submit(engineSubmission("Same crash as the previous ticket."));
        service.drainQueue();

        ArgumentCaptor<String> targetCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RelationsPatch> patchCap =
                ArgumentCaptor.forClass(RelationsPatch.class);
        verify(ticketService).updateRelations(
                targetCap.capture(), patchCap.capture());
        assertThat(targetCap.getValue()).isEqualTo("uuid-target");
        assertThat(patchCap.getValue().getAddRootCauseOf())
                .containsExactly("uuid-extra-1");

        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload())
                .containsEntry("decision", "merge_into")
                .containsEntry("ticketId", "uuid-target");
    }

    @Test
    void merge_into_without_target_writes_failure_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "merge_into"));   // no target!

        service.submit(engineSubmission("Some text."));
        service.drainQueue();

        verify(ticketService, never()).updateRelations(any(), any());
        verify(ticketService, never()).createTicket(any());
        InboxItemDocument item = captureInbox();
        assertThat(item.getTitle()).contains("could not be triaged");
        assertThat(item.getPayload()).containsEntry("decision", "failed");
    }

    // ─── discard decision ───────────────────────────────────────────

    @Test
    void discard_writes_only_inbox_item() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "discard",
                        "category", "project_data",
                        "reason", "This is about your project data, not Vance."));

        service.submit(engineSubmission("I'm missing document X."));
        service.drainQueue();

        verify(ticketService, never()).createTicket(any());
        verify(ticketService, never()).updateRelations(any(), any());
        InboxItemDocument item = captureInbox();
        assertThat(item.getTitle()).contains("not opened");
        assertThat(item.getPayload())
                .containsEntry("decision", "discard")
                .containsEntry("category", "project_data");
    }

    @Test
    void discard_recognises_nonsense_and_unrelated_categories() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "discard",
                        "category", "nonsense",
                        "reason", "no signal"));
        service.submit(engineSubmission("asdf"));
        service.drainQueue();
        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload()).containsEntry("category", "nonsense");
    }

    // ─── failure paths ──────────────────────────────────────────────

    @Test
    void light_llm_exception_writes_failure_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("provider down", null));

        service.submit(engineSubmission("anything"));
        service.drainQueue();

        verify(ticketService, never()).createTicket(any());
        InboxItemDocument item = captureInbox();
        assertThat(item.getTitle()).contains("could not be triaged");
        assertThat(item.getPayload())
                .containsEntry("decision", "failed")
                .containsEntry("error", "LightLlmException");
    }

    @Test
    void missing_decision_field_writes_failure_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("reason", "no decision"));

        service.submit(engineSubmission("anything"));
        service.drainQueue();

        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload()).containsEntry("decision", "failed");
    }

    @Test
    void unknown_decision_value_writes_failure_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "create_ticket"));  // not in enum

        service.submit(engineSubmission("anything"));
        service.drainQueue();

        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload()).containsEntry("decision", "failed");
    }

    // ─── reporter routing ───────────────────────────────────────────

    @Test
    void service_account_skips_inbox_but_still_processes_ticket() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket",
                        "derivedTitle", "T", "derivedType", "bug",
                        "derivedSeverity", "low",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        SubmissionRequest req = SubmissionRequest.builder()
                .text("Daemon hit an error.")
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.SERVICE_ACCOUNT)
                        .serviceAccount("_daemon-prod-01")
                        .build())
                .build();
        service.submit(req);
        service.drainQueue();

        verify(ticketService).createTicket(any());
        verifyNoInteractions(inboxItemService);
    }

    @Test
    void reporter_missing_userId_skips_inbox() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "discard",
                        "category", "vague", "reason", "ok"));

        SubmissionRequest req = SubmissionRequest.builder()
                .text("anything")
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE)
                        // userId + tenantId both missing
                        .build())
                .build();
        service.submit(req);
        service.drainQueue();

        verifyNoInteractions(inboxItemService);
    }

    // ─── LightLlm request shape ─────────────────────────────────────

    @Test
    void light_llm_request_carries_recipe_pebble_vars_and_tenant() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "discard",
                        "category", "vague", "reason", "ok"));

        service.submit(engineSubmission("My report text here."));
        service.drainQueue();

        ArgumentCaptor<LightLlmRequest> reqCap =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(reqCap.capture());
        LightLlmRequest req = reqCap.getValue();
        assertThat(req.getRecipeName()).isEqualTo("fook");
        // Primary triage path runs against the system tenant `_vance`
        // for uniform decision quality. The reporter-tenant fallback
        // only fires on UnknownModelException — see separate test.
        assertThat(req.getTenantId()).isEqualTo("_vance");
        assertThat(req.getPebbleVars()).containsKey("text");
        assertThat(req.getPebbleVars()).containsKey("candidates");
        // text is the raw report verbatim — NOT JSON-wrapped.
        assertThat(req.getPebbleVars().get("text"))
                .isEqualTo("My report text here.");
    }

    @Test
    void candidates_get_passed_as_list_of_maps_with_relations_nested() {
        TicketCandidate c = TicketCandidate.builder()
                .id("uuid-c")
                .type("bug")
                .severity("medium")
                .status("new")
                .title("Candidate title")
                .description("Candidate body")
                .relations(TicketRelations.builder()
                        .duplicateOf(null)
                        .rootCauseOf(List.of("rc-1"))
                        .relatedTo(List.of())
                        .build())
                .build();
        when(ticketService.searchSimilar(any(), anyInt()))
                .thenReturn(List.of(c));
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "discard",
                        "category", "vague", "reason", "ok"));

        service.submit(engineSubmission("Some report."));
        service.drainQueue();

        ArgumentCaptor<LightLlmRequest> cap =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        Object candidates = cap.getValue().getPebbleVars().get("candidates");
        assertThat(candidates).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) candidates;
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id")).isEqualTo("uuid-c");
        @SuppressWarnings("unchecked")
        Map<String, Object> rel = (Map<String, Object>) list.get(0).get("relations");
        assertThat(rel.get("rootCauseOf")).isEqualTo(List.of("rc-1"));
    }

    // ─── transport-mode → NewTicketPayload mapping ──────────────────

    @Test
    void mode_automatic_creates_ticket_with_transport_auto_approval() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq("fook.upstream.mode")))
                .thenReturn("automatic");
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket",
                        "derivedTitle", "T", "derivedType", "bug",
                        "derivedSeverity", "low",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission("report text"));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> cap =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(cap.capture());
        assertThat(cap.getValue().getTransportApproval()).isEqualTo("auto");
    }

    @Test
    void mode_manual_creates_ticket_with_pending_approval() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq("fook.upstream.mode")))
                .thenReturn("manual");
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket",
                        "derivedTitle", "T", "derivedType", "bug",
                        "derivedSeverity", "low",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission("text"));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> cap =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(cap.capture());
        assertThat(cap.getValue().getTransportApproval()).isEqualTo("pending");

        InboxItemDocument item = captureInbox();
        assertThat(item.getBody()).contains("waiting for an admin");
        assertThat(item.getPayload()).containsEntry("transportMode", "manual");
    }

    @Test
    void mode_never_creates_ticket_with_none_approval() {
        when(settingService.getStringValueCascade(any(), any(), any(),
                eq("fook.upstream.mode")))
                .thenReturn("never");
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket",
                        "derivedTitle", "T", "derivedType", "bug",
                        "derivedSeverity", "low",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission("text"));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> cap =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(cap.capture());
        assertThat(cap.getValue().getTransportApproval()).isEqualTo("none");
        InboxItemDocument item = captureInbox();
        assertThat(item.getBody()).contains("stays local");
    }

    @Test
    void inboxItemId_back_pointer_is_stamped_on_ticket() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("decision", "new_ticket",
                        "derivedTitle", "T", "derivedType", "bug",
                        "derivedSeverity", "low",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");
        // create() returns the saved document with an id set.
        InboxItemDocument withId = InboxItemDocument.builder()
                .id("inbox-42")
                .build();
        when(inboxItemService.create(any())).thenReturn(withId);

        service.submit(engineSubmission("text"));
        service.drainQueue();

        verify(ticketService).setInboxItemId("uuid-new", "inbox-42");
    }

    // ─── system → reporter tenant fallback ──────────────────────────

    @Test
    void system_tenant_unknown_model_falls_back_to_reporter_tenant() {
        // First call (system tenant) throws UnknownModelException; the
        // fallback call (reporter tenant) succeeds. Both go through
        // lightLlm.callForJson.
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenAnswer(inv -> {
            LightLlmRequest req = inv.getArgument(0);
            if ("_vance".equals(req.getTenantId())) {
                throw new AiModelResolver.UnknownModelException(
                        "Cannot resolve 'default:analyze': tenant '_vance' has "
                                + "no 'ai.default.provider' / 'ai.default.model' settings");
            }
            return Map.of("decision", "discard",
                    "category", "vague", "reason", "ok");
        });

        service.submit(engineSubmission("any report"));
        service.drainQueue();

        ArgumentCaptor<LightLlmRequest> cap =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm, times(2)).callForJson(cap.capture());
        // First attempt: _vance, no projectId.
        assertThat(cap.getAllValues().get(0).getTenantId()).isEqualTo("_vance");
        assertThat(cap.getAllValues().get(0).getProjectId()).isNull();
        // Fallback attempt: reporter's tenant + project.
        assertThat(cap.getAllValues().get(1).getTenantId()).isEqualTo("acme");
        assertThat(cap.getAllValues().get(1).getProjectId()).isEqualTo("p1");

        // Inbox-Item ist trotzdem geschrieben — Triage hat geklappt.
        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload()).containsEntry("decision", "discard");
    }

    @Test
    void unknown_model_without_usable_fallback_writes_failure_inbox() {
        // Reporter is themselves in the `_vance` tenant — no fallback
        // possible, the exception must bubble through to the
        // failure-inbox path.
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new AiModelResolver.UnknownModelException(
                        "Cannot resolve 'default:analyze': tenant '_vance' has no "
                                + "'ai.default.provider' / 'ai.default.model' settings"));

        SubmissionRequest req = SubmissionRequest.builder()
                .text("system-tenant report")
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE)
                        .userId("admin")
                        .tenantId("_vance")
                        .build())
                .build();
        service.submit(req);
        service.drainQueue();

        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload())
                .containsEntry("decision", "failed")
                .containsEntry("error", "UnknownModelException");
        // Only the primary attempt — no fallback because reporter
        // tenant == system tenant.
        verify(lightLlm, times(1)).callForJson(any());
    }

    @Test
    void non_unknown_model_failures_do_not_trigger_fallback() {
        // Schema-validation-after-retries, provider 5xx, etc. should NOT
        // be retried — only UnknownModelException triggers the fallback.
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("provider exhausted", null));

        service.submit(engineSubmission("any"));
        service.drainQueue();

        verify(lightLlm, times(1)).callForJson(any());
        InboxItemDocument item = captureInbox();
        assertThat(item.getPayload()).containsEntry("decision", "failed");
    }

    // ─── parseTriageResult unit tests ───────────────────────────────

    @Test
    void parse_recognises_all_three_decisions() {
        assertThat(FookService.parseTriageResult(Map.of(
                "decision", "new_ticket")).getDecision())
                .isEqualTo(TriageResult.Decision.NEW_TICKET);
        assertThat(FookService.parseTriageResult(Map.of(
                "decision", "merge_into",
                "targetTicketId", "x")).getDecision())
                .isEqualTo(TriageResult.Decision.MERGE_INTO);
        assertThat(FookService.parseTriageResult(Map.of(
                "decision", "discard")).getDecision())
                .isEqualTo(TriageResult.Decision.DISCARD);
    }

    @Test
    void parse_extracts_derived_fields() {
        TriageResult r = FookService.parseTriageResult(Map.of(
                "decision", "new_ticket",
                "derivedTitle", "T",
                "derivedType", "feature",
                "derivedSeverity", "low"));
        assertThat(r.getDerivedTitle()).isEqualTo("T");
        assertThat(r.getDerivedType()).isEqualTo("feature");
        assertThat(r.getDerivedSeverity()).isEqualTo("low");
    }

    @Test
    void parse_is_case_insensitive_on_decision() {
        assertThat(FookService.parseTriageResult(Map.of(
                "decision", "NEW_TICKET")).getDecision())
                .isEqualTo(TriageResult.Decision.NEW_TICKET);
    }

    @Test
    void parse_throws_on_missing_decision() {
        assertThatThrownBy(() -> FookService.parseTriageResult(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision");
    }

    @Test
    void parse_throws_on_unknown_decision() {
        assertThatThrownBy(() -> FookService.parseTriageResult(Map.of(
                "decision", "open_ticket")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open_ticket");
    }

    // ─── bilingual description ──────────────────────────────────────

    @Test
    void english_only_submission_keeps_description_as_original() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "new_ticket",
                        "derivedTitle", "Brain crash on boot",
                        "derivedType", "bug",
                        "derivedSeverity", "high",
                        "englishTranslation", "",   // already English
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission(
                "Brain crashes on boot when recipes.yaml is missing."));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> cap =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(cap.capture());
        // Description is the original verbatim — no divider, no
        // duplicated text.
        assertThat(cap.getValue().getDescription())
                .isEqualTo("Brain crashes on boot when recipes.yaml is missing.");
        assertThat(cap.getValue().getDescription()).doesNotContain("--- Original:");
    }

    @Test
    void non_english_submission_gets_bilingual_description() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of(
                        "decision", "new_ticket",
                        "derivedTitle", "Brain crashes on boot",
                        "derivedType", "bug",
                        "derivedSeverity", "high",
                        "englishTranslation",
                            "Brain crashes on boot when recipes.yaml is missing.",
                        "reason", "ok"));
        when(ticketService.createTicket(any())).thenReturn("uuid-new");

        service.submit(engineSubmission(
                "Brain stürzt beim Boot ab, wenn die recipes.yaml fehlt."));
        service.drainQueue();

        ArgumentCaptor<NewTicketPayload> cap =
                ArgumentCaptor.forClass(NewTicketPayload.class);
        verify(ticketService).createTicket(cap.capture());
        String body = cap.getValue().getDescription();
        // English first, then divider, then original.
        assertThat(body)
                .startsWith("Brain crashes on boot when recipes.yaml is missing.")
                .contains("--- Original:")
                .contains("Brain stürzt beim Boot ab");
        // English text comes BEFORE the divider.
        int dividerAt = body.indexOf("--- Original:");
        assertThat(body.substring(0, dividerAt)).doesNotContain("stürzt");
    }

    @Test
    void bilingualDescription_helper_handles_null_and_blank() {
        assertThat(FookService.bilingualDescription("hi", null)).isEqualTo("hi");
        assertThat(FookService.bilingualDescription("hi", "")).isEqualTo("hi");
        assertThat(FookService.bilingualDescription("hi", "  ")).isEqualTo("hi");
        assertThat(FookService.bilingualDescription("orig", "english"))
                .isEqualTo("english\n\n--- Original:\n\norig");
    }

    @Test
    void fallbackTitle_picks_first_non_blank_line_and_caps_length() {
        assertThat(FookService.fallbackTitle("\n  \nFirst real line\nsecond"))
                .isEqualTo("First real line");
        String huge = "x".repeat(200);
        assertThat(FookService.fallbackTitle(huge))
                .hasSize(120)
                .endsWith("...");
        assertThat(FookService.fallbackTitle("")).isEqualTo("(untitled)");
        assertThat(FookService.fallbackTitle(null)).isEqualTo("(untitled)");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private SubmissionRequest engineSubmission(String text) {
        return SubmissionRequest.builder()
                .text(text)
                .reporter(reporterEngine())
                .context(TicketContext.builder()
                        .projectId("p1")
                        .sessionId("s1")
                        .processId("proc-1")
                        .recipe("arthur")
                        .engine("arthur")
                        .build())
                .build();
    }

    private TicketReporter reporterEngine() {
        return TicketReporter.builder()
                .kind(TicketReporter.Kind.ENGINE)
                .userId("alice")
                .tenantId("acme")
                .build();
    }

    private InboxItemDocument captureInbox() {
        ArgumentCaptor<InboxItemDocument> cap =
                ArgumentCaptor.forClass(InboxItemDocument.class);
        verify(inboxItemService, times(1)).create(cap.capture());
        return cap.getValue();
    }
}
