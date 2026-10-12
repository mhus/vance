package de.mhus.vance.brain.wowbagger.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.action.ScopeLevel;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

class ScriptWorkerTest {

    @TempDir
    Path tempDir;

    private ScriptExecutor executor;
    private DocumentService documents;
    private WorkspaceService workspace;
    private RecipeResolver recipes;
    private AiModelResolver models;
    private ScriptWorker worker;
    private ResolvedRecipe recipe;
    private ThinkProcessDocument process;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = mock(ScriptExecutor.class);
        documents = mock(DocumentService.class);
        workspace = mock(WorkspaceService.class);
        recipes = mock(RecipeResolver.class);
        models = mock(AiModelResolver.class);
        ThinkEngineService engines = mock(ThinkEngineService.class);
        ThinkEngineContext context = mock(ThinkEngineContext.class);
        when(context.tools()).thenReturn(mock(ContextToolsApi.class));
        when(engines.newContext(any())).thenReturn(context);
        ObjectProvider<ThinkEngineService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engines);
        worker = new ScriptWorker(
                executor,
                documents,
                workspace,
                provider,
                recipes,
                models,
                JsonMapper.builder().build());
        recipe = mock(ResolvedRecipe.class);
        when(recipe.engine()).thenReturn("hactar");
        when(recipe.internal()).thenReturn(true);
        when(recipe.params()).thenReturn(Map.of());
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("p1");
    }

    private static WorkerSpec spec(boolean jsonl, String workerScript) {
        return new WorkerSpec(
                "wowbagger-script-worker", "triage", null, jsonl, false, workerScript, "wowbagger-abc", 2, "ollama:*");
    }

    private PreparedWorker prepareWorkspaceScript(boolean jsonl, String code) throws Exception {
        Path file = tempDir.resolve("triage.js");
        Files.writeString(file, code, StandardCharsets.UTF_8);
        when(workspace.readablePath("acme", "p1", "wowbagger-abc", "triage.js")).thenReturn(file);
        return worker.prepare(new WorkerSetup(process, spec(jsonl, "triage.js"), recipe));
    }

    @Test
    void selects_internal_hactar_profiles_only() {
        assertThat(worker.supports(recipe)).isTrue();
        when(recipe.internal()).thenReturn(false);
        assertThat(worker.supports(recipe)).isFalse();
    }

    @Test
    void runs_the_script_once_per_chunk_with_records_task_chunk_and_the_call_policy() throws Exception {
        when(executor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult(List.of("plain", Map.of("intent", "refund")), Duration.ZERO, ""));
        PreparedWorker prepared = prepareWorkspaceScript(true, "return vance.params.records.map(r => r);");

        WorkerReply reply = prepared.process(List.of("{\"m\":\"a\"}", "{\"m\":\"b\"}"), new ChunkContext(3, 6));

        assertThat(prepared.kind()).isEqualTo("script");
        assertThat(prepared.resolvedModel()).isNull();
        assertThat(reply.lines()).containsExactly("plain", "{\"intent\":\"refund\"}");
        ArgumentCaptor<ScriptRequest> request = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(executor).run(request.capture());
        ScriptRequest r = request.getValue();
        assertThat(r.scopeLevel()).isEqualTo(ScopeLevel.PROCESS_SCOPED);
        assertThat(r.timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(r.callPolicy()).isInstanceOf(WorkerCallPolicy.class);
        Map<?, ?> args = (Map<?, ?>) r.bindings().get("args");
        assertThat(args.get("records")).isEqualTo(List.of(Map.of("m", "a"), Map.of("m", "b")));
        assertThat(args.get("task")).isEqualTo("triage");
        assertThat(args.get("chunk")).isEqualTo(Map.of("index", 3, "startRecord", 6L));
    }

    @Test
    void profile_script_document_wins_and_sets_the_timeout() {
        when(recipe.params()).thenReturn(Map.of("script", "_vance/scripts/triage.js", "timeoutSeconds", 30));
        LookupResult hit = mock(LookupResult.class);
        when(hit.content()).thenReturn("return [];");
        when(documents.lookupCascade("acme", "p1", "_vance/scripts/triage.js")).thenReturn(Optional.of(hit));
        when(executor.run(any(ScriptRequest.class))).thenReturn(new ScriptResult(List.of(), Duration.ZERO, ""));

        worker.prepare(new WorkerSetup(process, spec(false, null), recipe)).process(List.of(), new ChunkContext(0, 0));

        ArgumentCaptor<ScriptRequest> request = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(executor).run(request.capture());
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(request.getValue().sourceName()).isEqualTo("_vance/scripts/triage.js");
    }

    @Test
    void the_call_policy_resolves_llm_recipes_and_gates_them() throws Exception {
        ResolvedRecipe deep = mock(ResolvedRecipe.class);
        when(deep.params()).thenReturn(Map.of("model", "default:deep"));
        when(recipes.resolve("acme", "p1", "ticket-deep")).thenReturn(Optional.of(deep));
        when(models.resolveOrDefault(any(), eq("acme"), eq("p1"), eq("proc-1")))
                .thenReturn(new AiModelResolver.Resolved("openai", "openai", "gpt-5", false));
        when(executor.run(any(ScriptRequest.class))).thenAnswer(inv -> {
            ScriptRequest r = inv.getArgument(0);
            r.callPolicy().beforeLlmCall("ticket-deep"); // what vance.llm does inside the script
            return new ScriptResult(List.of("x"), Duration.ZERO, "");
        });
        PreparedWorker prepared = prepareWorkspaceScript(false, "…");

        assertThatThrownBy(() -> prepared.process(List.of("r"), new ChunkContext(0, 0)))
                .hasMessageContaining("'openai:gpt-5'")
                .hasMessageContaining("not approved");
    }

    @Test
    void result_contract_is_enforced() throws Exception {
        PreparedWorker prepared = prepareWorkspaceScript(false, "…");

        when(executor.run(any(ScriptRequest.class))).thenReturn(new ScriptResult("nope", Duration.ZERO, ""));
        assertThatThrownBy(() -> prepared.process(List.of("a"), new ChunkContext(0, 0)))
                .hasMessageContaining("must return a list");

        when(executor.run(any(ScriptRequest.class))).thenReturn(new ScriptResult(List.of("a"), Duration.ZERO, ""));
        assertThatThrownBy(() -> prepared.process(List.of("a", "b"), new ChunkContext(0, 0)))
                .hasMessageContaining("returned 1 results for 2 records");

        when(executor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult(List.of("two\nlines"), Duration.ZERO, ""));
        assertThatThrownBy(() -> prepared.process(List.of("a"), new ChunkContext(0, 0)))
                .hasMessageContaining("spans several lines");

        when(executor.run(any(ScriptRequest.class)))
                .thenThrow(new ScriptExecutionException(ScriptExecutionException.ErrorClass.GUEST_EXCEPTION, "boom"));
        assertThatThrownBy(() -> prepared.process(List.of("a"), new ChunkContext(0, 0)))
                .hasMessageContaining("worker script failed [GUEST_EXCEPTION]: boom");
    }

    @Test
    void a_worker_without_any_script_is_refused_at_prepare() {
        assertThatThrownBy(() -> worker.prepare(new WorkerSetup(process, spec(false, null), recipe)))
                .hasMessageContaining("has no script");
    }

    @Test
    void an_empty_script_is_refused_at_prepare() throws Exception {
        assertThatThrownBy(() -> prepareWorkspaceScript(false, "  ")).hasMessageContaining("is empty");
        verifyNoScriptRun();
    }

    private void verifyNoScriptRun() {
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.never()).run(any(ScriptRequest.class));
    }

    @Test
    void unreadable_workspace_script_names_the_root() {
        when(workspace.readablePath(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tempDir.resolve("missing.js"));

        assertThatThrownBy(() -> worker.prepare(new WorkerSetup(process, spec(false, "missing.js"), recipe)))
                .hasMessageContaining("unreadable in RootDir 'wowbagger-abc'");
    }
}
