package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link VanceSupportRequestTool}. The
 * downstream {@link FookService} is mocked — we just verify the
 * tool's contract: param validation, rate-limit, context capture,
 * and the shape of the result the LLM sees.
 *
 * <p>Single-text input model: the tool takes one parameter,
 * {@code text}, and Fook derives type / severity / title from it.
 */
class VanceSupportRequestToolTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext(
            "acme", "proj-1", "sess-1", "proc-1", "alice");

    private FookService fookService;
    private ThinkProcessService thinkProcessService;
    private VanceSupportRequestTool tool;

    @BeforeEach
    void setUp() {
        fookService = mock(FookService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        when(thinkProcessService.findById(any())).thenReturn(Optional.empty());
        when(fookService.submit(any())).thenReturn("sub-xyz");
        tool = new VanceSupportRequestTool(fookService, thinkProcessService);
    }

    // ─── metadata ───────────────────────────────────────────────────

    @Test
    void metadata_is_consistent() {
        assertThat(tool.name()).isEqualTo("vance_support_request");
        assertThat(tool.description()).contains("Vance itself");
        assertThat(tool.description()).contains("Do NOT use");
        assertThat(tool.primary()).isTrue();
        assertThat(tool.labels()).contains("write", "side-effect");
    }

    @Test
    void description_names_the_text_parameter_explicitly() {
        // Regression for the schema-mismatch loop we saw in the live
        // mhus/gesundheit session: the LLM first called with
        // {"description": "..."} (rejected, no tool-result), then
        // retried with {"text": "..."}. Driver of the wrong key was
        // the description's loose phrasing ("describe what happened");
        // pinning the exact param name in the prose stops the retry.
        String desc = tool.description();
        assertThat(desc).contains("`text`");
        assertThat(desc).contains("description")
                .contains("message")
                .contains("body");
    }

    @Test
    void schema_has_only_text_param_and_is_required() {
        Map<String, Object> schema = tool.paramsSchema();
        assertThat(schema.get("type")).isEqualTo("object");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsOnlyKeys("text");

        @SuppressWarnings("unchecked")
        Map<String, Object> textProp = (Map<String, Object>) props.get("text");
        assertThat(textProp.get("type")).isEqualTo("string");
        assertThat(textProp.get("description").toString())
                .contains("Fook")
                .contains("type")
                .contains("severity");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("text");
    }

    // ─── happy path ─────────────────────────────────────────────────

    @Test
    void invoke_queues_submission_and_returns_id() {
        Map<String, Object> result = tool.invoke(
                Map.of("text", "Brain crashes on boot when recipes.yaml is missing."),
                CTX);

        assertThat(result).containsEntry("submissionId", "sub-xyz");
        assertThat(result).containsEntry("status", "queued");
        assertThat(result).containsEntry("remainingBudget", 2);
        assertThat(result.get("note").toString()).contains("Fook is triaging");

        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        SubmissionRequest req = cap.getValue();
        assertThat(req.getText())
                .isEqualTo("Brain crashes on boot when recipes.yaml is missing.");
        assertThat(req.getReporter().getKind())
                .isEqualTo(TicketReporter.Kind.ENGINE);
        assertThat(req.getReporter().getUserId()).isEqualTo("alice");
        assertThat(req.getReporter().getTenantId()).isEqualTo("acme");
        assertThat(req.getContext().getProjectId()).isEqualTo("proj-1");
        assertThat(req.getContext().getSessionId()).isEqualTo("sess-1");
        assertThat(req.getContext().getProcessId()).isEqualTo("proc-1");
    }

    @Test
    void invoke_enriches_context_with_recipe_and_engine_when_process_resolves() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setRecipeName("arthur");
        process.setThinkEngine("arthur");
        when(thinkProcessService.findById("proc-1")).thenReturn(Optional.of(process));

        tool.invoke(Map.of("text", "Some report."), CTX);

        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        TicketContext ctx = cap.getValue().getContext();
        assertThat(ctx.getRecipe()).isEqualTo("arthur");
        assertThat(ctx.getEngine()).isEqualTo("arthur");
    }

    // ─── rate-limit ─────────────────────────────────────────────────

    @Test
    void rate_limit_blocks_after_three_calls_per_process() {
        Map<String, Object> args = Map.of("text", "something");

        for (int i = 0; i < 3; i++) {
            Map<String, Object> result = tool.invoke(args, CTX);
            assertThat(result).containsEntry("status", "queued");
        }

        assertThatThrownBy(() -> tool.invoke(args, CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("budget exhausted");
        verify(fookService, org.mockito.Mockito.times(3)).submit(any());
    }

    @Test
    void rate_limit_is_per_process_not_global() {
        Map<String, Object> args = Map.of("text", "something");
        ToolInvocationContext other = new ToolInvocationContext(
                "acme", "proj-1", "sess-2", "proc-OTHER", "alice");

        for (int i = 0; i < 3; i++) tool.invoke(args, CTX);
        Map<String, Object> result = tool.invoke(args, other);
        assertThat(result).containsEntry("remainingBudget", 2);
    }

    // ─── param validation ──────────────────────────────────────────

    @Test
    void missing_text_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("text");
        verifyNoInteractions(fookService);
    }

    @Test
    void blank_text_throws() {
        assertThatThrownBy(() -> tool.invoke(Map.of("text", "   "), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("text");
    }

    @Test
    void invoke_accepts_description_alias_when_text_missing() {
        // LLM-side fallback: if the model ignores the schema and
        // passes the payload under one of the common aliases
        // (description/message/body/report/content) we treat it as
        // the text instead of letting the Jeltz schema-loop burn an
        // extra correction cycle. The pre-fix live trace had Arthur
        // call {"description":"..."} first; this turns that into a
        // single successful invocation.
        Map<String, Object> result = tool.invoke(
                Map.of("description", "Brain crashes on boot."),
                CTX);

        assertThat(result).containsEntry("status", "queued");
        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        assertThat(cap.getValue().getText())
                .isEqualTo("Brain crashes on boot.");
    }

    @Test
    void invoke_prefers_text_over_alias_when_both_present() {
        // text wins — alias only fires as a fallback. Without this,
        // a future model that emits both keys could silently overwrite
        // the canonical field with a stale alias value.
        tool.invoke(
                Map.of("text", "canonical body", "description", "stale alias body"),
                CTX);

        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        assertThat(cap.getValue().getText()).isEqualTo("canonical body");
    }

    @Test
    void missing_text_error_message_names_the_aliases() {
        // Belt-and-braces: if the LLM somehow lands on an alias we
        // *don't* accept (e.g. "summary"), the error tells it both
        // the correct name and which wrong names are well-known —
        // saves an extra Jeltz round-trip.
        assertThatThrownBy(() -> tool.invoke(Map.of("summary", "x"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("text")
                .hasMessageContaining("description")
                .hasMessageContaining("message")
                .hasMessageContaining("body");
    }

    // ─── context guards ─────────────────────────────────────────────

    @Test
    void missing_processId_throws() {
        ToolInvocationContext noProc = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", null, "alice");
        assertThatThrownBy(() -> tool.invoke(Map.of("text", "x"), noProc))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("think-process");
    }

    @Test
    void missing_userId_throws() {
        ToolInvocationContext noUser = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", null);
        assertThatThrownBy(() -> tool.invoke(Map.of("text", "x"), noUser))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("userId");
    }
}
