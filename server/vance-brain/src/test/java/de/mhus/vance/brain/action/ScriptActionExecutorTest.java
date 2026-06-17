package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptActionExecutorTest {

    private ScriptExecutor scriptExecutor;
    private DocumentService documentService;
    private ToolDispatcher toolDispatcher;
    private ScriptActionExecutor exec;

    private final TriggerContext ctx = TriggerContext.standalone(
            "t1", "p1", "alice", "corr-1", "scheduler:foo", null);

    @BeforeEach
    void setUp() {
        scriptExecutor = mock(ScriptExecutor.class);
        documentService = mock(DocumentService.class);
        toolDispatcher = mock(ToolDispatcher.class);
        exec = new ScriptActionExecutor(scriptExecutor, documentService, toolDispatcher);
    }

    // ──────────────────── Document source — happy paths ────────────────────

    @Test
    void document_script_with_void_return_yields_SUCCESS() {
        stubDocument("scripts/x.js", "doc('x');");
        stubExecutorReturns(null);

        ActionResult r = run(documentScript("scripts/x.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).isEmpty();
    }

    @Test
    void document_script_with_success_wrapper_yields_SUCCESS_minus_success_field() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(Map.of("success", true, "tally", 42));

        ActionResult r = run(documentScript("scripts/x.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("tally", 42).doesNotContainKey("success");
    }

    @Test
    void document_script_returning_success_false_yields_BUSINESS_ERROR() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(Map.of("success", false, "error", "bad input"));

        ActionResult r = run(documentScript("scripts/x.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).isEqualTo("bad input");
    }

    // ──────────────────── Source-loading failures ────────────────────

    @Test
    void document_not_found_yields_script_not_found_error() {
        when(documentService.lookupCascade(any(), any(), eq("scripts/missing.js")))
                .thenReturn(Optional.empty());

        ActionResult r = run(documentScript("scripts/missing.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("scripts/missing.js");
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void empty_document_body_yields_TECHNICAL_ERROR() {
        stubDocument("scripts/empty.js", "");

        ActionResult r = run(documentScript("scripts/empty.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("empty");
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void workspace_source_with_no_workspace_service_yields_script_not_found() {
        // setUp() instantiates the executor with workspaceService=null
        // via the 3-arg constructor, so workspace lookups fail clearly.
        ActionResult r = run(new TriggerAction.Script(
                ScriptSource.WORKSPACE, "scratch", "gen/x.js", null, null, null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("script not found");
        verify(scriptExecutor, never()).run(any());
    }

    @Test
    void workspace_source_loads_and_runs_when_workspace_service_present() throws Exception {
        de.mhus.vance.shared.workspace.WorkspaceService ws =
                mock(de.mhus.vance.shared.workspace.WorkspaceService.class);
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("script", ".js");
        java.nio.file.Files.writeString(tmp, "({success: true, ok: 1})");
        when(ws.readablePath(eq("t1"), eq("p1"), eq("scratch"), eq("gen/x.js")))
                .thenReturn(tmp);
        ScriptActionExecutor exec = new ScriptActionExecutor(
                scriptExecutor, documentService, toolDispatcher, ws);
        stubExecutorReturns(java.util.Map.of("success", true, "ok", 1));

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Script(ScriptSource.WORKSPACE, "scratch", "gen/x.js",
                        null, null, null),
                ctx, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("ok", 1);
    }

    @Test
    void workspace_source_with_missing_rootdir_yields_script_not_found() {
        de.mhus.vance.shared.workspace.WorkspaceService ws =
                mock(de.mhus.vance.shared.workspace.WorkspaceService.class);
        when(ws.readablePath(any(), any(), any(), any()))
                .thenThrow(new de.mhus.vance.shared.workspace.WorkspaceException("Unknown RootDir"));
        ScriptActionExecutor exec = new ScriptActionExecutor(
                scriptExecutor, documentService, toolDispatcher, ws);

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Script(ScriptSource.WORKSPACE, "gone", "x.js",
                        null, null, null),
                ctx, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("script not found");
        verify(scriptExecutor, never()).run(any());
    }

    // ──────────────────── Exception mapping ────────────────────

    @Test
    void script_executor_timeout_yields_TIMEOUT() {
        stubDocument("scripts/loop.js", "while(true);");
        when(scriptExecutor.run(any())).thenThrow(new ScriptExecutionException(
                ScriptExecutionException.ErrorClass.TIMEOUT, "wall clock"));

        ActionResult r = run(documentScript("scripts/loop.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TIMEOUT);
    }

    @Test
    void script_executor_guest_exception_yields_BUSINESS_ERROR() {
        stubDocument("scripts/throw.js", "throw new Error('boom');");
        when(scriptExecutor.run(any())).thenThrow(new ScriptExecutionException(
                ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "Error: boom"));

        ActionResult r = run(documentScript("scripts/throw.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).contains("boom");
    }

    @Test
    void unexpected_runtime_exception_yields_TECHNICAL_ERROR() {
        stubDocument("scripts/x.js", "...");
        when(scriptExecutor.run(any())).thenThrow(new IllegalStateException("graal blew up"));

        ActionResult r = run(documentScript("scripts/x.js", null));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("unexpected");
    }

    // ──────────────────── Bindings + timeout passthrough ────────────────────

    @Test
    void params_passed_to_script_as_args_binding() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(null);

        run(documentScript("scripts/x.js", Map.of("a", 1, "b", "two")));

        ArgumentCaptor<ScriptRequest> captor = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(scriptExecutor).run(captor.capture());
        assertThat(captor.getValue().bindings()).containsKey("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) captor.getValue().bindings().get("args");
        assertThat(args).containsEntry("a", 1).containsEntry("b", "two");
    }

    @Test
    void null_params_pass_as_empty_args_map() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(null);

        run(documentScript("scripts/x.js", null));

        ArgumentCaptor<ScriptRequest> captor = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(scriptExecutor).run(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) captor.getValue().bindings().get("args");
        assertThat(args).isEmpty();
    }

    @Test
    void timeoutSeconds_overrides_default() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(null);

        run(new TriggerAction.Script(ScriptSource.DOCUMENT, null, "scripts/x.js",
                7, null, null));

        ArgumentCaptor<ScriptRequest> captor = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(scriptExecutor).run(captor.capture());
        assertThat(captor.getValue().timeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void missing_timeoutSeconds_uses_30s_default() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(null);

        run(documentScript("scripts/x.js", null));

        ArgumentCaptor<ScriptRequest> captor = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(scriptExecutor).run(captor.capture());
        assertThat(captor.getValue().timeout()).isEqualTo(ScriptActionExecutor.DEFAULT_TIMEOUT);
    }

    @Test
    void sourceName_uses_doc_prefix_for_DOCUMENT_source() {
        stubDocument("scripts/x.js", "...");
        stubExecutorReturns(null);

        run(documentScript("scripts/x.js", null));

        ArgumentCaptor<ScriptRequest> captor = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(scriptExecutor).run(captor.capture());
        assertThat(captor.getValue().sourceName()).isEqualTo("doc:scripts/x.js");
    }

    @Test
    void actionType_returns_script_class() {
        assertThat(exec.actionType()).isEqualTo(TriggerAction.Script.class);
    }

    // ──────────────────── Helpers ────────────────────

    private ActionResult run(TriggerAction.Script action) {
        return exec.execute(new ActionInvocation<>(action, ctx, TriggerKind.SCHEDULER));
    }

    private void stubDocument(String path, String content) {
        LookupResult lookup = new LookupResult(
                path, content, LookupResult.Source.PROJECT, null);
        when(documentService.lookupCascade(eq("t1"), eq("p1"), eq(path)))
                .thenReturn(Optional.of(lookup));
    }

    private void stubExecutorReturns(Object value) {
        when(scriptExecutor.run(any()))
                .thenReturn(new ScriptResult(value, Duration.ofMillis(5)));
    }

    private static TriggerAction.Script documentScript(String path, Map<String, Object> params) {
        return new TriggerAction.Script(
                ScriptSource.DOCUMENT, null, path, null, params, null);
    }
}
