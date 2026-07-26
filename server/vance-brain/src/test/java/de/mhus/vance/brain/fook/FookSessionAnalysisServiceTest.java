package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link FookSessionAnalysisService}'s agentic
 * loop. Chat history, LightLlm and the ticket store are mocked; the
 * metric service is real (SimpleMeterRegistry) so outcome counters are
 * exercised. The loop is driven by stubbing consecutive
 * {@code callForJson} returns — one per turn.
 */
class FookSessionAnalysisServiceTest {

    private ChatMessageService chatMessageService;
    private LightLlmService lightLlm;
    private FookTicketService ticketService;
    private SimpleMeterRegistry registry;
    private FookSessionAnalysisService service;

    @BeforeEach
    void setUp() {
        chatMessageService = mock(ChatMessageService.class);
        lightLlm = mock(LightLlmService.class);
        ticketService = mock(FookTicketService.class);
        registry = new SimpleMeterRegistry();
        service = new FookSessionAnalysisService(
                chatMessageService, lightLlm, ticketService,
                new MetricService(registry));
        when(chatMessageService.activeHistory(any(), any(), any()))
                .thenReturn(List.of(
                        msg(ChatRole.USER, "Please save the file.", 0),
                        msg(ChatRole.ASSISTANT, "Calling save_file tool.", 1),
                        msg(ChatRole.SYSTEM, "save_file threw NullPointerException.", 2)));
    }

    // ─── loop outcomes ──────────────────────────────────────────────

    @Test
    void loop_searches_reads_then_finishes_and_writes_report() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(
                Map.of("action", "search", "query", "NullPointer"),
                Map.of("action", "read", "from", 1, "to", 2),
                Map.of("action", "finish", "useful", true,
                        "report", "save_file NPEs when path is null."));

        service.enqueue(job());
        service.drainQueue();

        verify(lightLlm, times(3)).callForJson(any());
        verify(ticketService).writeAnalysis(
                "uuid-1", "save_file NPEs when path is null.");
        assertThat(counter(FookSessionAnalysisService.OUTCOME_WRITTEN)).isEqualTo(1.0);
    }

    @Test
    void finish_not_useful_stamps_skipped_and_writes_no_sidecar() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("action", "finish", "useful", false, "report", ""));

        service.enqueue(job());
        service.drainQueue();

        verify(ticketService, never()).writeAnalysis(any(), any());
        verify(ticketService).setAnalysisStatus(
                "uuid-1", FookTicketService.ANALYSIS_SKIPPED);
        assertThat(counter(FookSessionAnalysisService.OUTCOME_SKIPPED_NOT_USEFUL))
                .isEqualTo(1.0);
    }

    @Test
    void finish_useful_but_blank_report_is_treated_as_skipped() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("action", "finish", "useful", true, "report", "   "));

        service.enqueue(job());
        service.drainQueue();

        verify(ticketService, never()).writeAnalysis(any(), any());
        verify(ticketService).setAnalysisStatus(
                "uuid-1", FookTicketService.ANALYSIS_SKIPPED);
    }

    @Test
    void running_out_of_steps_without_finish_stamps_exhausted() {
        // Model never finishes — keeps searching every turn.
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("action", "search", "query", "x"));

        service.enqueue(job());
        service.drainQueue();

        verify(lightLlm, times(FookSessionAnalysisService.DEFAULT_MAX_STEPS)).callForJson(any());
        verify(ticketService, never()).writeAnalysis(any(), any());
        verify(ticketService).setAnalysisStatus(
                "uuid-1", FookTicketService.ANALYSIS_SKIPPED);
        assertThat(counter(FookSessionAnalysisService.OUTCOME_EXHAUSTED)).isEqualTo(1.0);
    }

    @Test
    void unknown_action_is_fed_back_and_loop_continues() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(
                Map.of("action", "teleport"),
                Map.of("action", "finish", "useful", true, "report", "ok"));

        service.enqueue(job());
        service.drainQueue();

        verify(lightLlm, times(2)).callForJson(any());
        verify(ticketService).writeAnalysis("uuid-1", "ok");
    }

    @Test
    void empty_history_skips_without_calling_the_model() {
        when(chatMessageService.activeHistory(any(), any(), any()))
                .thenReturn(List.of());

        service.enqueue(job());
        service.drainQueue();

        verifyNoInteractions(lightLlm);
        verify(ticketService).setAnalysisStatus(
                "uuid-1", FookTicketService.ANALYSIS_SKIPPED);
        assertThat(counter(FookSessionAnalysisService.OUTCOME_SKIPPED_NO_SESSION))
                .isEqualTo(1.0);
    }

    @Test
    void llm_failure_stamps_failed_and_is_not_fatal() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("provider down", null));

        service.enqueue(job());
        service.drainQueue();   // must not throw

        verify(ticketService, never()).writeAnalysis(any(), any());
        verify(ticketService).setAnalysisStatus(
                "uuid-1", FookTicketService.ANALYSIS_FAILED);
        assertThat(counter(FookSessionAnalysisService.OUTCOME_FAILED)).isEqualTo(1.0);
    }

    @Test
    void loop_runs_against_reporter_tenant_and_project() {
        when(lightLlm.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("action", "finish", "useful", true, "report", "x"));

        service.enqueue(job());
        service.drainQueue();

        ArgumentCaptor<LightLlmRequest> cap =
                ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo("fook-session-analysis");
        // Reporter tenant, NOT _vance — session data locality + privacy.
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("p1");
        assertThat(req.getProcessId()).isEqualTo("proc-1");
        // First turn already carries a seeded overview + the step budget.
        assertThat(req.getPebbleVars()).containsKey("observations");
        assertThat(req.getPebbleVars().get("observations").toString())
                .contains("OVERVIEW");
        assertThat(req.getPebbleVars().get("stepsLeft"))
                .isEqualTo(FookSessionAnalysisService.DEFAULT_MAX_STEPS);
        assertThat(req.getPebbleVars().get("ticketTitle")).isEqualTo("Crash on save");
    }

    // ─── tool ops (static) ──────────────────────────────────────────

    @Test
    void overview_reports_count_roles_and_index_range() {
        String o = FookSessionAnalysisService.overview(List.of(
                msg(ChatRole.USER, "hi", 0),
                msg(ChatRole.ASSISTANT, "hello", 1)));
        assertThat(o)
                .contains("OVERVIEW: 2 messages")
                .contains("indices 0..1")
                .contains("USER")
                .contains("ASSISTANT");
    }

    @Test
    void search_returns_indices_of_keyword_matches() {
        List<ChatMessageDocument> ms = List.of(
                msg(ChatRole.USER, "save the file", 0),
                msg(ChatRole.SYSTEM, "NullPointerException in save_file", 1),
                msg(ChatRole.ASSISTANT, "unrelated", 2));
        String r = FookSessionAnalysisService.search(ms, "nullpointer", false);
        assertThat(r).contains("[1]").doesNotContain("[0]").doesNotContain("[2]");
    }

    @Test
    void search_reports_no_matches() {
        String r = FookSessionAnalysisService.search(
                List.of(msg(ChatRole.USER, "hello", 0)), "zzz", false);
        assertThat(r).contains("no matches");
    }

    @Test
    void grep_supports_regex_and_reports_invalid_pattern() {
        List<ChatMessageDocument> ms = List.of(
                msg(ChatRole.SYSTEM, "error code 42", 0),
                msg(ChatRole.SYSTEM, "error code 7", 1));
        assertThat(FookSessionAnalysisService.search(ms, "code \\d\\d", true))
                .contains("[0]").doesNotContain("[1]");
        assertThat(FookSessionAnalysisService.search(ms, "(unclosed", true))
                .contains("invalid regex");
    }

    @Test
    void read_returns_full_content_of_range_and_clamps() {
        List<ChatMessageDocument> ms = List.of(
                msg(ChatRole.USER, "first message body", 0),
                msg(ChatRole.ASSISTANT, "second message body", 1));
        String r = FookSessionAnalysisService.read(ms, 0, 5);   // hi clamped
        assertThat(r)
                .contains("first message body")
                .contains("second message body");
        assertThat(FookSessionAnalysisService.read(ms, 9, 9))
                .contains("out of range");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private FookSessionAnalysisService.AnalysisJob job() {
        return FookSessionAnalysisService.AnalysisJob.builder()
                .ticketId("uuid-1")
                .submissionId("sub-1")
                .tenantId("acme")
                .projectId("p1")
                .sessionId("s1")
                .processId("proc-1")
                .reason("distinct crash")
                .triageNote("repro present")
                .ticketTitle("Crash on save")
                .ticketType("bug")
                .engine("arthur")
                .recipe("arthur")
                .build();
    }

    private static ChatMessageDocument msg(ChatRole role, String content, int seq) {
        return ChatMessageDocument.builder()
                .role(role)
                .content(content)
                .createdAt(Instant.parse("2026-07-27T00:00:0" + seq + "Z"))
                .build();
    }

    private double counter(String outcome) {
        io.micrometer.core.instrument.Counter c = registry.find(
                FookSessionAnalysisService.METRIC)
                .tag("outcome", outcome)
                .counter();
        return c == null ? 0.0 : c.count();
    }
}
