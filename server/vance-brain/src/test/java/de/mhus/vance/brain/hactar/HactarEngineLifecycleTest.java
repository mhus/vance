package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lifecycle test — drives the {@link HactarEngine} through the
 * full state machine (READY → DRAFTING → VALIDATING → DONE; optional
 * EXECUTING when {@code executeOnDone=true}) plus the recovery loop
 * (syntax-error → re-draft, up to {@code maxRecoveries}). Uses a
 * {@link ScriptedChatModel} for deterministic LLM replies and a real
 * {@link JsValidationService} so the parse-only check exercises the
 * same GraalJS parser as production.
 */
class HactarEngineLifecycleTest {

    private static Engine graalEngine;
    private static JsValidationService jsValidationService;

    @BeforeAll
    static void buildGraalEngine() {
        graalEngine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        jsValidationService = new JsValidationService(graalEngine);
    }

    @AfterAll
    static void closeGraalEngine() {
        graalEngine.close();
    }

    private ThinkProcessService thinkProcessService;
    private ProcessEventEmitter eventEmitter;
    private ObjectMapper objectMapper;
    private EngineChatFactory engineChatFactory;
    private EnginePromptResolver enginePromptResolver;
    private PromptTemplateRenderer promptTemplateRenderer;
    private LlmCallTracker llmCallTracker;
    private ToolDispatcher toolDispatcher;
    private ScriptExecutor scriptExecutor;
    private DocumentService documentService;
    private SkillResolver skillResolver;
    private SessionService sessionService;
    private RecipeResolver recipeResolver;
    private LaneScheduler laneScheduler;
    private ChatMessageService chatMessageService;
    private ThinkEngineService thinkEngineService;
    private org.springframework.beans.factory.ObjectProvider<ThinkEngineService>
            thinkEngineServiceProvider;
    private ScriptedChatModel chatModel;
    private ThinkEngineContext ctx;

    private HactarEngine engine;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        objectMapper = JsonMapper.builder().build();
        engineChatFactory = mock(EngineChatFactory.class);
        enginePromptResolver = mock(EnginePromptResolver.class);
        llmCallTracker = mock(LlmCallTracker.class);
        toolDispatcher = mock(ToolDispatcher.class);
        // No tools registered by default — Tool inventory rendering
        // returns "" so the prompt simply omits the section.
        when(toolDispatcher.resolve(anyString(), any()))
                .thenReturn(java.util.Optional.empty());
        scriptExecutor = mock(ScriptExecutor.class);
        // Default: EXECUTING returns a string value, used by both
        // execute-on-done tests and any others that accidentally
        // enter EXECUTING.
        when(scriptExecutor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult("default-result",
                        java.time.Duration.ofMillis(5)));
        documentService = mock(DocumentService.class);
        // Default: no manuals — renderManualInventory returns "" and
        // the prompt template's manual section is omitted.
        when(documentService.listByPrefixCascade(anyString(), any(), anyString()))
                .thenReturn(java.util.Map.of());
        skillResolver = mock(SkillResolver.class);
        sessionService = mock(SessionService.class);
        // Default: no session backing the process — scopeFor returns
        // (tenant, null, null), and no skills resolve. Engine flows
        // through without skill-side rendering.
        when(sessionService.findBySessionId(anyString()))
                .thenReturn(java.util.Optional.empty());
        when(skillResolver.resolve(any(), anyString()))
                .thenReturn(java.util.Optional.empty());

        recipeResolver = mock(RecipeResolver.class);
        laneScheduler = mock(LaneScheduler.class);
        chatMessageService = mock(ChatMessageService.class);
        thinkEngineService = mock(ThinkEngineService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ThinkEngineService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(thinkEngineService);
        thinkEngineServiceProvider = provider;

        // Real renderer — Pebble has no I/O, cheap to construct, and
        // exercises the exact rendering path the production code uses.
        promptTemplateRenderer = new PromptTemplateRenderer();

        chatModel = new ScriptedChatModel();
        AiChat aiChat = mock(AiChat.class);
        when(aiChat.chatModel()).thenReturn(chatModel);
        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(bundle);

        // Prompt resolver returns the bundled-default text — the
        // renderer treats it as a Pebble template, and the {{ goal }}
        // placeholder gets filled from the engine's ctxMap.
        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenAnswer(inv -> "System: draft a script. Goal = {{ goal }}.");

        ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.<SteerMessage>of());

        doAnswer(inv -> null).when(thinkProcessService)
                .replaceEngineParams(anyString(), any());

        // Build real phase components — exercises the dispatch path
        // end-to-end. Slart's lifecycle test mocks the phases; for DT
        // the phase logic is the bulk of what the lifecycle tests are
        // meant to validate, so we wire the production components and
        // mock only the leaf dependencies (LLM, scripts, IO).
        de.mhus.vance.brain.hactar.phases.HactarContextRenderer
                contextRenderer =
                new de.mhus.vance.brain.hactar.phases.HactarContextRenderer(
                        toolDispatcher, documentService,
                        skillResolver, sessionService);
        de.mhus.vance.brain.hactar.phases.FramingPhase framingPhase =
                new de.mhus.vance.brain.hactar.phases.FramingPhase(
                        engineChatFactory, enginePromptResolver,
                        promptTemplateRenderer, llmCallTracker, contextRenderer);
        de.mhus.vance.brain.hactar.phases.ReviewingPhase reviewingPhase =
                new de.mhus.vance.brain.hactar.phases.ReviewingPhase(
                        thinkProcessService, recipeResolver, laneScheduler,
                        chatMessageService, thinkEngineServiceProvider);
        de.mhus.vance.brain.hactar.phases.DraftingPhase draftingPhase =
                new de.mhus.vance.brain.hactar.phases.DraftingPhase(
                        engineChatFactory, enginePromptResolver,
                        promptTemplateRenderer, llmCallTracker, contextRenderer);
        de.mhus.vance.brain.hactar.phases.ValidatingPhase validatingPhase =
                new de.mhus.vance.brain.hactar.phases.ValidatingPhase(
                        jsValidationService);
        de.mhus.vance.brain.hactar.phases.ExecutingPhase executingPhase =
                new de.mhus.vance.brain.hactar.phases.ExecutingPhase(
                        scriptExecutor, toolDispatcher);
        de.mhus.vance.brain.hactar.phases.LoadingPhase loadingPhase =
                new de.mhus.vance.brain.hactar.phases.LoadingPhase(
                        documentService);

        engine = new HactarEngine(
                thinkProcessService,
                eventEmitter,
                objectMapper,
                loadingPhase,
                framingPhase,
                reviewingPhase,
                draftingPhase,
                validatingPhase,
                executingPhase);
    }

    // ──────────────────── Happy path ────────────────────

    @Test
    void start_persistsInitialStateAsReady_andSchedulesTurn() {
        ThinkProcessDocument process = newProcess();

        engine.start(process, ctx);

        HactarState state = readState(process);
        assertThat(state.getStatus()).isEqualTo(HactarStatus.READY);
        assertThat(state.getGoal()).isEqualTo("write me a hello-script");
        assertThat(state.getMaxRecoveries()).isEqualTo(5);
        assertThat(state.isExecuteOnDone()).isFalse();
        verify(eventEmitter).scheduleTurn(process.getId());
    }

    @Test
    void runTurns_validDraftOnFirstTry_reachesDone() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 42; })();\n```");

        int turns = drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getGeneratedCode())
                .isEqualTo("(function () { return 42; })();");
        assertThat(finalState.getValidationErrors()).isEmpty();
        assertThat(finalState.getRecoveryCount()).isZero();

        // READY → DRAFTING → VALIDATING → DONE = 3 advancing turns,
        // plus the terminal turn that observes DONE and closes.
        assertThat(turns).isEqualTo(3);
        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.DONE));
    }

    @Test
    void runTurns_recoversOnSyntaxError_reachesDone() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        // First draft has a missing closing brace; second draft is
        // valid. Recovery loop must catch the parser error, re-prompt,
        // and accept the corrected body.
        chatModel.script(
                "```javascript\n(function () { return 1 + ;\n```",
                "```javascript\n(function () { return 1 + 2; })();\n```");

        drainTurns(process, 20);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getRecoveryCount()).isEqualTo(1);
        assertThat(finalState.getGeneratedCode())
                .isEqualTo("(function () { return 1 + 2; })();");
        assertThat(finalState.getValidationErrors()).isEmpty();
        // Both scripted replies consumed.
        assertThat(chatModel.remaining()).isZero();
    }

    @Test
    void runTurns_recoveryExhausted_endsInFailed() {
        ThinkProcessDocument process = newProcess();
        // Tighten the budget so the test stays small.
        process.getEngineParams().put(HactarEngine.MAX_RECOVERIES_KEY, 2);
        engine.start(process, ctx);
        // Every draft is broken — recoveryCount climbs 0→1→2 and the
        // engine bails into FAILED.
        chatModel.script(
                "```javascript\nfunction broken(\n```",
                "```javascript\nfunction still_broken(\n```",
                "```javascript\nfunction always_broken(\n```");

        drainTurns(process, 30);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(finalState.getRecoveryCount()).isEqualTo(2);
        assertThat(finalState.getFailureReason())
                .contains("Exceeded maxRecoveries");
        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.STALE));
    }

    @Test
    void runTurns_replyWithoutFence_countsAsRecovery() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        // First reply is bare prose (no fence) — the engine must treat
        // that as a validation failure and re-prompt. Second reply is
        // properly fenced.
        chatModel.script(
                "Here is the script: function foo() { return 1; }",
                "```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 20);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getRecoveryCount()).isEqualTo(1);
    }

    @Test
    void runTurns_withExecuteOnDone_passesThroughExecutingPhase() {
        when(scriptExecutor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult("hello, world",
                        java.time.Duration.ofMillis(42)));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 'hello, world'; })();\n```");

        int turns = drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getExecutionResult()).isEqualTo("hello, world");
        assertThat(finalState.getExecutionDurationMs()).isEqualTo(42L);
        assertThat(finalState.getExecutionError()).isNull();
        // READY → DRAFTING → VALIDATING → EXECUTING → DONE = 4.
        assertThat(turns).isEqualTo(4);
    }

    @Test
    void runTurns_scriptExecutionFails_endsInFailed_noRecoveryLoop() {
        // Runtime errors from the executor go straight to FAILED —
        // they're not fed back into DRAFTING (DRAFTING corrects
        // syntax, not runtime semantics; a runtime-error loop would
        // burn LLM tokens for an unknowable outcome).
        when(scriptExecutor.run(any(ScriptRequest.class)))
                .thenThrow(new ScriptExecutionException(
                        ScriptExecutionException.ErrorClass.TIMEOUT,
                        "Script exceeded its @timeout"));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { while (true) {} })();\n```");

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(finalState.getExecutionError())
                .contains("Script exceeded its @timeout");
        assertThat(finalState.getExecutionErrorClass()).isEqualTo("TIMEOUT");
        assertThat(finalState.getFailureReason())
                .contains("Script execution failed (TIMEOUT)");
        // No DRAFTING recovery — only one drafting attempt.
        assertThat(finalState.getRecoveryCount()).isZero();
    }

    @Test
    void runTurns_scriptArgsAndToolsHandedToExecutor() {
        // Capture the ScriptRequest the engine builds — verify the
        // scriptArgs map lands as the `args` binding and the tool
        // surface is narrowed to scriptAllowedTools.
        org.mockito.ArgumentCaptor<ScriptRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ScriptRequest.class);
        when(scriptExecutor.run(requestCaptor.capture()))
                .thenReturn(new ScriptResult(7, java.time.Duration.ofMillis(3)));

        ThinkProcessDocument process = newProcess();
        process.setRecipeName("script-developer");
        process.getEngineParams().put(HactarEngine.EXECUTE_ON_DONE_KEY, true);
        process.getEngineParams().put(
                HactarEngine.SCRIPT_ARGS_KEY,
                Map.of("a", 3, "b", 4));
        process.getEngineParams().put(
                HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY,
                List.of("doc_write_text"));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return args.a + args.b; })();\n```");

        drainTurns(process, 10);

        ScriptRequest req = requestCaptor.getValue();
        assertThat(req.language()).isEqualTo("js");
        assertThat(req.bindings()).containsKey("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) req.bindings().get("args");
        assertThat(args).containsEntry("a", 3).containsEntry("b", 4);
        // ContextToolsApi's allowed-set is locked-down to what the
        // caller declared — exercises the narrow-tool-surface
        // contract.
        assertThat(req.tools().allowed()).containsExactly("doc_write_text");
        // Recipe name flows through to the script's vance.context.recipe.
        assertThat(req.recipeName()).isEqualTo("script-developer");
    }

    // ──────────────────── Tool inventory ────────────────────

    @Test
    void drafting_rendersToolInventory_whenScriptAllowedToolsSet() {
        // Stub two tools in the dispatcher. The engine must render
        // them into the system prompt so the LLM picks the right
        // names (the #1 quality lever for generated scripts).
        Tool docWrite = mock(Tool.class);
        when(docWrite.name()).thenReturn("doc_write_text");
        when(docWrite.description()).thenReturn("Write a text document.");
        when(docWrite.paramsSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "title", Map.of("type", "string")),
                "required", List.of("path", "content")));

        Tool procRun = mock(Tool.class);
        when(procRun.name()).thenReturn("process_run");
        when(procRun.description()).thenReturn("Synchronously spawn a sub-worker.");
        when(procRun.paramsSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "goal", Map.of("type", "string"),
                        "recipe", Map.of("type", "string"),
                        "steerContent", Map.of("type", "string")),
                "required", List.of("name", "goal", "recipe")));

        when(toolDispatcher.resolve(eq("doc_write_text"), any()))
                .thenReturn(java.util.Optional.of(
                        new ToolDispatcher.Resolved(
                                docWrite,
                                mock(de.mhus.vance.brain.tools.ToolSource.class))));
        when(toolDispatcher.resolve(eq("process_run"), any()))
                .thenReturn(java.util.Optional.of(
                        new ToolDispatcher.Resolved(
                                procRun,
                                mock(de.mhus.vance.brain.tools.ToolSource.class))));

        // Override the prompt resolver to return a minimal template
        // that just emits the toolInventory variable — so this test
        // verifies the engine's rendering, not the bundled prompt.
        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("{% if toolInventory %}TOOLS:\n{{ toolInventory }}{% endif %}");

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY,
                List.of("doc_write_text", "process_run"));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        // The first (and only) LLM call's system message must include
        // both tool names + their required-args lines.
        assertThat(chatModel.calls()).hasSize(1);
        ChatRequest request = chatModel.calls().get(0);
        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                request.messages().get(0)).text();
        assertThat(systemText)
                .contains("**doc_write_text**")
                .contains("Write a text document.")
                .contains("Required: path, content")
                .contains("**process_run**")
                .contains("Required: name, goal, recipe");
    }

    @Test
    void drafting_rendersManualInventory_whenManualPathsSet() {
        // Stub the document cascade — two manuals under one folder,
        // sourced from project and bundled defaults respectively. The
        // engine must list them by stem + folder + source.
        DocumentService.class.toString(); // keep import alive
        when(documentService.listByPrefixCascade(
                eq("acme"), eq("test-project"), eq("_vance/manuals/")))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "_vance/manuals/tool-conventions.md",
                        new LookupResult("_vance/manuals/tool-conventions.md",
                                "...", LookupResult.Source.PROJECT, null),
                        "_vance/manuals/data-export.md",
                        new LookupResult("_vance/manuals/data-export.md",
                                "...", LookupResult.Source.RESOURCE, null))));

        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("{% if manualInventory %}MANUALS:\n{{ manualInventory }}"
                        + "{% else %}NO MANUALS{% endif %}");

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.MANUAL_PATHS_KEY,
                List.of("_vance/manuals/"));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        ChatRequest request = chatModel.calls().get(0);
        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                request.messages().get(0)).text();
        assertThat(systemText)
                .contains("MANUALS:")
                .contains("**data-export**")
                .contains("(folder: _vance/manuals/, source: resource)")
                .contains("**tool-conventions**")
                .contains("(folder: _vance/manuals/, source: project)");
    }

    @Test
    void drafting_normalizesManualPaths_appendsTrailingSlash() {
        // Pass "manuals" (no trailing slash) — engine must normalise.
        when(documentService.listByPrefixCascade(
                anyString(), any(), eq("_vance/manuals/")))
                .thenReturn(java.util.Map.of(
                        "_vance/manuals/x.md",
                        new LookupResult("_vance/manuals/x.md", "...",
                                LookupResult.Source.PROJECT, null)));
        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("{% if manualInventory %}{{ manualInventory }}{% endif %}");

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.MANUAL_PATHS_KEY,
                List.of("_vance/manuals"));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                chatModel.calls().get(0).messages().get(0)).text();
        assertThat(systemText).contains("**x**");
    }

    // ──────────────────── Skill guidance ────────────────────

    @Test
    void drafting_includesSkillGuidance_whenScriptArchitectSkillActive() {
        // One script-architect-tagged skill: contributes promptExtension
        // + an INLINE reference doc + a manualPaths entry. All three
        // must surface in the rendered system prompt.
        ResolvedSkill skill = new ResolvedSkill(
                "essay-script-architect",
                "Essay-Script Skill",
                "Conventions for essay-orchestrator scripts",
                "1.0",
                List.of(),
                "Always use process_run for chapter-loops; never enumerate by hand.",
                List.of("process_run", "doc_write_text"),
                List.of("skill-manuals/"),
                List.of(new ResolvedSkill.ReferenceDoc(
                        "chapter-loop pattern",
                        "Spawn one Ford sub-worker per chapter via process_run.",
                        SkillReferenceDocLoadMode.INLINE,
                        null)),
                List.of(),
                List.of("script-architect", "essay"),
                true,
                SkillScope.PROJECT);
        when(skillResolver.resolve(any(), eq("essay-script-architect")))
                .thenReturn(java.util.Optional.of(skill));

        // Skill manualPaths fold into the manual inventory — the
        // engine queries the cascade for that folder too.
        when(documentService.listByPrefixCascade(
                anyString(), any(), eq("skill-manuals/")))
                .thenReturn(java.util.Map.of(
                        "skill-manuals/pattern.md",
                        new LookupResult("skill-manuals/pattern.md", "...",
                                LookupResult.Source.PROJECT, null)));

        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn(
                        "{% if manualInventory %}MANUALS:\n{{ manualInventory }}{% endif %}"
                        + "{% if skillGuidance %}\nGUIDANCE:\n{{ skillGuidance }}{% endif %}");

        ThinkProcessDocument process = newProcess();
        process.setActiveSkills(List.of(
                ActiveSkillRefEmbedded.builder()
                        .name("essay-script-architect")
                        .fromRecipe(true)
                        .build()));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                chatModel.calls().get(0).messages().get(0)).text();
        // promptExtension surfaces.
        assertThat(systemText).contains("Always use process_run for chapter-loops");
        // INLINE reference doc surfaces with its title.
        assertThat(systemText)
                .contains("chapter-loop pattern")
                .contains("Spawn one Ford sub-worker per chapter");
        // Skill-supplied manualPaths fold into the manual inventory.
        assertThat(systemText).contains("**pattern**");
    }

    @Test
    void drafting_skipsSkill_withoutScriptArchitectTag() {
        // Skill is active but lacks the script-architect tag — must
        // be ignored even though it has a promptExtension. Prevents
        // generic project-skills (essay-writer, etc.) from bleeding
        // into the DRAFTING prompt.
        ResolvedSkill irrelevantSkill = new ResolvedSkill(
                "essay-writer",
                "Essay Writer",
                "User-facing essay writing skill — not for DT",
                "1.0",
                List.of(),
                "Always cite sources in (vgl. ..., JJJJ) format.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("essay"),
                true,
                SkillScope.PROJECT);
        when(skillResolver.resolve(any(), eq("essay-writer")))
                .thenReturn(java.util.Optional.of(irrelevantSkill));

        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("{% if skillGuidance %}GUIDANCE:\n{{ skillGuidance }}"
                        + "{% else %}NO-GUIDANCE{% endif %}");

        ThinkProcessDocument process = newProcess();
        process.setActiveSkills(List.of(
                ActiveSkillRefEmbedded.builder()
                        .name("essay-writer")
                        .fromRecipe(false)
                        .build()));
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                chatModel.calls().get(0).messages().get(0)).text();
        assertThat(systemText).contains("NO-GUIDANCE");
        // The skill's promptExtension must not leak.
        assertThat(systemText).doesNotContain("Always cite sources");
    }

    @Test
    void drafting_omitsInventory_whenScriptAllowedToolsEmpty() {
        // No scriptAllowedTools set — Pebble template's else-branch
        // kicks in. The engine still renders cleanly.
        when(enginePromptResolver.resolve(any(), anyString(), anyString()))
                .thenReturn("{% if toolInventory %}TOOLS:\n{{ toolInventory }}"
                        + "{% else %}NO TOOLS{% endif %}");

        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        ChatRequest request = chatModel.calls().get(0);
        String systemText = ((dev.langchain4j.data.message.SystemMessage)
                request.messages().get(0)).text();
        assertThat(systemText).contains("NO TOOLS");
        assertThat(systemText).doesNotContain("TOOLS:");
    }

    // ──────────────────── Load-mode (scriptPath) ────────────────────

    @Test
    void loadMode_skipsGeneration_validatesLoadedScript() {
        // Set scriptPath; document cascade returns a valid IIFE.
        // Engine must skip FRAMING/DRAFTING entirely: LOADING →
        // VALIDATING → DONE. No LLM calls.
        String loaded = "(function () { return 42; })();";
        when(documentService.lookupCascade(
                eq("acme"), eq("test-project"), eq("scripts/hello.js")))
                .thenReturn(java.util.Optional.of(new LookupResult(
                        "scripts/hello.js", loaded,
                        LookupResult.Source.PROJECT, null)));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.SCRIPT_PATH_KEY, "scripts/hello.js");
        engine.start(process, ctx);

        int turns = drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getScriptPath()).isEqualTo("scripts/hello.js");
        assertThat(finalState.getGeneratedCode()).isEqualTo(loaded);
        // No drafting attempts.
        assertThat(finalState.getRecoveryCount()).isZero();
        // READY → LOADING → VALIDATING → DONE = 3 advancing turns.
        assertThat(turns).isEqualTo(3);
        // Crucially: ZERO LLM calls in load-mode.
        assertThat(chatModel.calls()).isEmpty();
    }

    @Test
    void loadMode_goalIsOptional() {
        // No goal anywhere — scriptPath is enough.
        when(documentService.lookupCascade(
                anyString(), any(), eq("scripts/x.js")))
                .thenReturn(java.util.Optional.of(new LookupResult(
                        "scripts/x.js", "(function () { return 1; })();",
                        LookupResult.Source.PROJECT, null)));

        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-deep-load");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        process.setEngineParams(new LinkedHashMap<>());
        process.getEngineParams().put(
                HactarEngine.SCRIPT_PATH_KEY, "scripts/x.js");
        // process.goal stays null; engineParams.goal absent.

        engine.start(process, ctx);
        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getGeneratedCode()).contains("return 1");
    }

    @Test
    void loadMode_invalidJs_failsWithoutDraftingRecovery() {
        // Loaded script has a syntax error. Load-mode forces
        // maxRecoveries=0, so VALIDATING goes straight to FAILED
        // instead of looping into DRAFTING (we didn't draft).
        when(documentService.lookupCascade(
                anyString(), any(), eq("scripts/broken.js")))
                .thenReturn(java.util.Optional.of(new LookupResult(
                        "scripts/broken.js", "function broken(",
                        LookupResult.Source.PROJECT, null)));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.SCRIPT_PATH_KEY, "scripts/broken.js");
        engine.start(process, ctx);

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(finalState.getValidationErrors()).isNotEmpty();
        assertThat(finalState.getFailureReason())
                .contains("Exceeded maxRecoveries");
        // Definitely no LLM-side recovery — chatModel was never called.
        assertThat(chatModel.calls()).isEmpty();
    }

    @Test
    void loadMode_documentNotFound_endsInFailed() {
        // lookupCascade returns empty — clear error message, no retry.
        when(documentService.lookupCascade(
                anyString(), any(), eq("scripts/missing.js")))
                .thenReturn(java.util.Optional.empty());

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.SCRIPT_PATH_KEY, "scripts/missing.js");
        engine.start(process, ctx);

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(finalState.getFailureReason())
                .contains("Script document not found")
                .contains("scripts/missing.js");
        assertThat(chatModel.calls()).isEmpty();
    }

    @Test
    void loadMode_withExecuteOnDone_runsLoadedScript() {
        // Load + execute pipeline — Cortex "Run this file" button.
        when(documentService.lookupCascade(
                anyString(), any(), eq("scripts/run-me.js")))
                .thenReturn(java.util.Optional.of(new LookupResult(
                        "scripts/run-me.js", "(function () { return 'done'; })();",
                        LookupResult.Source.PROJECT, null)));
        when(scriptExecutor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult("done",
                        java.time.Duration.ofMillis(7)));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                HactarEngine.SCRIPT_PATH_KEY, "scripts/run-me.js");
        process.getEngineParams().put(HactarEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);

        int turns = drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getExecutionResult()).isEqualTo("done");
        // READY → LOADING → VALIDATING → EXECUTING → DONE = 4.
        assertThat(turns).isEqualTo(4);
    }

    // ──────────────────── FRAMING + REVIEWING ────────────────────

    @Test
    void framingEnabled_noReviewerConfigured_drawsPlanThenDrafts() {
        // framingEnabled=true but no reviewerRecipe and no parent
        // recipeName → reviewer resolves to null → REVIEWING is
        // skipped and the engine flows straight into DRAFTING with
        // the plan sketch in the prompt.
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.FRAMING_ENABLED_KEY, true);
        engine.start(process, ctx);
        // Two scripted LLM replies: framing sketch + drafting body.
        chatModel.script(
                "## Goal recap\nWrite a hello-world script.\n\n## Approach\nReturn a string.",
                "```javascript\n(function () { return 'hello'; })();\n```");

        int turns = drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getPlanSketch())
                .contains("Goal recap")
                .contains("Write a hello-world script");
        assertThat(finalState.getReviewerVerdict()).isEqualTo("SKIPPED");
        assertThat(finalState.getFramingRecoveryCount()).isZero();

        // READY → FRAMING → REVIEWING → DRAFTING → VALIDATING → DONE
        // = 5 advancing turns.
        assertThat(turns).isEqualTo(5);
        // Both LLM replies were consumed.
        assertThat(chatModel.remaining()).isZero();
    }

    @Test
    void framingEnabled_reviewerApproves_goesStraightToDrafting() {
        // Reviewer recipe configured + APPROVED on first try.
        configureApprovingReviewer("script-developer-reviewer");

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.FRAMING_ENABLED_KEY, true);
        process.getEngineParams().put(
                HactarEngine.REVIEWER_RECIPE_KEY,
                "script-developer-reviewer");
        engine.start(process, ctx);
        // Only ONE LLM reply is consumed by the engine here (FRAMING).
        // DRAFTING is the second. The reviewer's reply is mocked via
        // chatMessageService.history, not via chatModel.
        chatModel.script(
                "## Goal recap\nReturn 42.\n\n## Approach\nIIFE returning 42.",
                "```javascript\n(function () { return 42; })();\n```");

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getReviewerVerdict()).isEqualTo("APPROVED");
        assertThat(finalState.getFramingRecoveryCount()).isZero();
        // The DRAFTING user message must have included the plan sketch.
        ChatRequest draftRequest = chatModel.calls().get(1);
        String userMsg = ((dev.langchain4j.data.message.UserMessage)
                draftRequest.messages().get(1)).singleText();
        assertThat(userMsg)
                .contains("Approved plan sketch")
                .contains("IIFE returning 42");
    }

    @Test
    void framingEnabled_reviewerRejectsOnce_thenApprovesViaRecovery() {
        // First REVIEWING returns REJECTED, second APPROVED. The
        // recovery loop must re-run FRAMING with the critique as hint
        // before advancing.
        java.util.concurrent.atomic.AtomicInteger reviewerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        configureReviewer("script-developer-reviewer", history -> {
            int n = reviewerCalls.incrementAndGet();
            return n == 1
                    ? "VERDICT: REJECTED\nThe plan misses error handling."
                    : "VERDICT: APPROVED\nLooks good.";
        });

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.FRAMING_ENABLED_KEY, true);
        process.getEngineParams().put(
                HactarEngine.REVIEWER_RECIPE_KEY,
                "script-developer-reviewer");
        engine.start(process, ctx);
        // Three LLM replies needed: FRAMING #1, FRAMING #2 (recovery),
        // DRAFTING. (Reviewer responses come from chatMessageService,
        // not from chatModel.)
        chatModel.script(
                "## Goal recap\nReturn 42.\n\n## Approach\nIIFE.",
                "## Goal recap\nReturn 42.\n\n## Approach\nIIFE with try/catch.",
                "```javascript\n(function () { try { return 42; } catch(e) { return -1; } })();\n```");

        drainTurns(process, 20);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getReviewerVerdict()).isEqualTo("APPROVED");
        assertThat(finalState.getFramingRecoveryCount()).isEqualTo(1);
        assertThat(reviewerCalls.get()).isEqualTo(2);
    }

    @Test
    void framingEnabled_reviewerKeepsRejecting_endsInFailed() {
        // Every reviewer reply rejects — engine bails into FAILED
        // once framingRecoveryCount hits maxFramingRecoveries.
        configureReviewer("script-developer-reviewer", history ->
                "VERDICT: REJECTED\nStill broken.");

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.FRAMING_ENABLED_KEY, true);
        process.getEngineParams().put(
                HactarEngine.REVIEWER_RECIPE_KEY,
                "script-developer-reviewer");
        process.getEngineParams().put(
                HactarEngine.MAX_FRAMING_RECOVERIES_KEY, 2);
        engine.start(process, ctx);
        // Two FRAMING attempts before the budget is exhausted; we
        // queue three plan-sketches in case the loop overshoots.
        chatModel.script(
                "## Plan v1",
                "## Plan v2",
                "## Plan v3");

        drainTurns(process, 20);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(finalState.getFramingRecoveryCount()).isEqualTo(2);
        assertThat(finalState.getFailureReason())
                .contains("Exceeded maxFramingRecoveries")
                .contains("Still broken");
    }

    @Test
    void framingEnabled_reviewerSpawnFails_skipsReviewAndContinues() {
        // The reviewer recipe doesn't resolve — engine logs, sets
        // verdict=SKIPPED, and advances to DRAFTING with the
        // unreviewed plan sketch. Plan-mode still produced value.
        when(recipeResolver.apply(anyString(), any(), eq("missing-reviewer"),
                any(), any()))
                .thenThrow(new RecipeResolver.UnknownRecipeException(
                        "missing-reviewer"));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.FRAMING_ENABLED_KEY, true);
        process.getEngineParams().put(
                HactarEngine.REVIEWER_RECIPE_KEY, "missing-reviewer");
        engine.start(process, ctx);
        chatModel.script(
                "## Goal recap\nReturn 1.",
                "```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getReviewerVerdict()).isEqualTo("SKIPPED");
        assertThat(finalState.getReviewerNotes())
                .contains("missing-reviewer")
                .contains("not found");
    }

    @Test
    void framingDisabled_defaultBehaviour_unchanged() {
        // No framingEnabled param set — engine takes the legacy fast
        // path READY → DRAFTING. The new statuses are never visited.
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        drainTurns(process, 10);

        HactarState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(finalState.getPlanSketch()).isNull();
        assertThat(finalState.getReviewerVerdict()).isNull();
        assertThat(finalState.getFramingRecoveryCount()).isZero();
        // Only ONE LLM call (DRAFTING) — no FRAMING.
        assertThat(chatModel.calls()).hasSize(1);
    }

    /**
     * Configures the reviewer recipe to resolve to a Ford-like child,
     * with the spawn + drive pipeline mocked so the child writes one
     * APPROVED-line assistant message into chatMessageService's history.
     */
    private void configureApprovingReviewer(String recipeName) {
        configureReviewer(recipeName, history ->
                "VERDICT: APPROVED\nPlan is sound.");
    }

    /**
     * Configures the reviewer chain end-to-end. The supplied function
     * decides what the reviewer "says" on each invocation (called when
     * the engine reads the child's history). Mocks
     * RecipeResolver.apply / ThinkEngineService.start / LaneScheduler.submit
     * / chatMessageService.history / ThinkEngineService.stop so the
     * engine sees a complete round-trip.
     */
    private void configureReviewer(
            String recipeName,
            java.util.function.Function<Void, String> replyForCall) {
        // The applied recipe (minimal — only what the engine reads).
        de.mhus.vance.brain.recipe.AppliedRecipe applied =
                mock(de.mhus.vance.brain.recipe.AppliedRecipe.class);
        when(applied.name()).thenReturn(recipeName);
        when(applied.engine()).thenReturn("ford");
        when(applied.params()).thenReturn(java.util.Map.of());
        when(applied.effectiveAllowedTools()).thenReturn(java.util.Set.<String>of());
        when(applied.connectionProfile()).thenReturn(null);
        when(applied.defaultActiveSkills()).thenReturn(java.util.List.<String>of());
        when(applied.allowedSkills()).thenReturn(null);
        when(applied.promptOverride()).thenReturn(null);
        when(applied.promptOverrideAppend()).thenReturn(null);
        when(applied.promptMode()).thenReturn(null);
        when(applied.dataRelayCorrection()).thenReturn(null);
        when(recipeResolver.apply(anyString(), any(), eq(recipeName), any(), any()))
                .thenReturn(applied);

        // Resolve "ford" engine; spec only needs name() + version().
        ThinkEngine fordStub = mock(ThinkEngine.class);
        when(fordStub.name()).thenReturn("ford");
        when(fordStub.version()).thenReturn("1.0");
        when(thinkEngineService.resolve(eq("ford")))
                .thenReturn(java.util.Optional.of(fordStub));

        // create() returns a stand-in child process.
        ThinkProcessDocument child = new ThinkProcessDocument();
        child.setId("reviewer-child-1");
        child.setTenantId("acme");
        child.setProjectId("test-project");
        child.setSessionId("sess-1");
        when(thinkProcessService.create(
                anyString(), any(), any(), anyString(),
                anyString(), anyString(),
                anyString(), any(), any(),
                any(), anyString(),
                any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(child);

        // The lane scheduler "executes" the steer immediately, then
        // returns a completed Future so .get() unblocks.
        when(laneScheduler.submit(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    Runnable r = inv.getArgument(1);
                    r.run();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });

        // chatMessageService returns a single-element history with the
        // reviewer's verdict line — engine reads getLastAssistantText.
        when(chatMessageService.history(anyString(), anyString(), eq("reviewer-child-1")))
                .thenAnswer(inv -> {
                    de.mhus.vance.shared.chat.ChatMessageDocument m =
                            new de.mhus.vance.shared.chat.ChatMessageDocument();
                    m.setRole(de.mhus.vance.api.chat.ChatRole.ASSISTANT);
                    m.setContent(replyForCall.apply(null));
                    return List.of(m);
                });
    }

    // ──────────────────── Idempotency ────────────────────

    @Test
    void terminalDoneStateIsIdempotentAcrossExtraTurns() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");
        drainTurns(process, 10);

        HactarState before = readState(process);
        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);
        HactarState after = readState(process);

        assertThat(after.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(after.getGeneratedCode()).isEqualTo(before.getGeneratedCode());
        // No extra LLM calls after DONE — chatModel queue stays at 0.
        assertThat(chatModel.remaining()).isZero();
    }

    @Test
    void terminalFailedStateIsIdempotent() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        HactarState s = readState(process);
        s.setStatus(HactarStatus.FAILED);
        s.setFailureReason("test-induced");
        engine.persistState(process, s);
        reset(eventEmitter);

        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);

        HactarState after = readState(process);
        assertThat(after.getStatus()).isEqualTo(HactarStatus.FAILED);
        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.STALE));
        verify(eventEmitter, times(0)).scheduleTurn(anyString());
    }

    // ──────────────────── Input validation ────────────────────

    @Test
    void start_withoutGoal_throws() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-deep-2");
        process.setTenantId("acme");
        process.setProjectId("p");
        process.setSessionId("s");
        process.setEngineParams(new LinkedHashMap<>());

        assertThatThrownBy(() -> engine.start(process, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires either a goal or a scriptPath");
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Test
    void summarizeForParent_onDone_withExecuteOnDone_returnsValue() {
        when(scriptExecutor.run(any(ScriptRequest.class)))
                .thenReturn(new ScriptResult(Map.of("ok", true, "sum", 7),
                        java.time.Duration.ofMillis(11)));

        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(HactarEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return {ok:true,sum:7}; })();\n```");
        drainTurns(process, 10);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.DONE);

        // Value-flavour summary: not the code, but the returned data
        // rendered as a fenced JSON block. Code length still in payload.
        assertThat(report.humanSummary())
                .contains("executed the generated script")
                .contains("```json")
                .contains("\"sum\":7");
        assertThat(report.payload())
                .containsEntry("status", "DONE")
                .containsEntry("executionDurationMs", 11L)
                .containsKey("executionResult");
    }

    @Test
    void summarizeForParent_onDone_returnsCodeBlock() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 42; })();\n```");
        drainTurns(process, 10);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.DONE);

        assertThat(report.humanSummary())
                .contains("```javascript")
                .contains("return 42");
        assertThat(report.payload())
                .containsEntry("status", "DONE")
                .containsKey("codeLength");
    }

    @Test
    void summarizeForParent_onFailed_carriesFailureReason() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        HactarState s = readState(process);
        s.setStatus(HactarStatus.FAILED);
        s.setFailureReason("LLM returned 451");
        engine.persistState(process, s);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.FAILED);

        assertThat(report.humanSummary()).contains("LLM returned 451");
        assertThat(report.payload()).containsEntry("status", "FAILED");
    }

    // ──────────────────── Helpers ────────────────────

    private int drainTurns(ThinkProcessDocument process, int cap) {
        for (int i = 0; i < cap; i++) {
            HactarState before = readState(process);
            if (isTerminal(before.getStatus())) {
                engine.runTurn(process, ctx);
                return i;
            }
            engine.runTurn(process, ctx);
        }
        throw new AssertionError("turn cap exceeded — possible infinite loop");
    }

    private static boolean isTerminal(HactarStatus s) {
        return s == HactarStatus.DONE || s == HactarStatus.FAILED;
    }

    private HactarState readState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(HactarEngine.STATE_KEY);
        if (raw == null) return HactarState.builder().build();
        return objectMapper.convertValue(raw, HactarState.class);
    }

    private static ThinkProcessDocument newProcess() {
        Map<String, Object> params = new LinkedHashMap<>();
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-deep-1");
        p.setTenantId("acme");
        p.setProjectId("test-project");
        p.setSessionId("sess-1");
        p.setGoal("write me a hello-script");
        p.setEngineParams(params);
        return p;
    }

    /**
     * Deterministic {@link ChatModel} stand-in — replays a fixed
     * sequence of reply strings. Tests drive the engine and pin
     * exactly what the LLM "would have said" at each call.
     */
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> responses = new ArrayDeque<>();
        private final java.util.List<ChatRequest> calls = new java.util.ArrayList<>();

        void script(String... entries) {
            responses.clear();
            for (String s : entries) responses.add(s);
        }

        int remaining() {
            return responses.size();
        }

        java.util.List<ChatRequest> calls() {
            return calls;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.add(request);
            if (responses.isEmpty()) {
                throw new IllegalStateException(
                        "ScriptedChatModel: no more scripted responses");
            }
            String text = responses.pop();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
