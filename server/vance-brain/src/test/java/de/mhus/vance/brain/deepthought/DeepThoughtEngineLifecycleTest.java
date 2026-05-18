package de.mhus.vance.brain.deepthought;

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

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ToolDispatcher;
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
 * Lifecycle test — drives the {@link DeepThoughtEngine} through the
 * full state machine (READY → DRAFTING → VALIDATING → DONE; optional
 * EXECUTING when {@code executeOnDone=true}) plus the recovery loop
 * (syntax-error → re-draft, up to {@code maxRecoveries}). Uses a
 * {@link ScriptedChatModel} for deterministic LLM replies and a real
 * {@link JsValidationService} so the parse-only check exercises the
 * same GraalJS parser as production.
 */
class DeepThoughtEngineLifecycleTest {

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
    private ScriptedChatModel chatModel;
    private ThinkEngineContext ctx;

    private DeepThoughtEngine engine;

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

        engine = new DeepThoughtEngine(
                thinkProcessService,
                eventEmitter,
                objectMapper,
                engineChatFactory,
                enginePromptResolver,
                promptTemplateRenderer,
                llmCallTracker,
                jsValidationService,
                toolDispatcher);
    }

    // ──────────────────── Happy path ────────────────────

    @Test
    void start_persistsInitialStateAsReady_andSchedulesTurn() {
        ThinkProcessDocument process = newProcess();

        engine.start(process, ctx);

        DeepThoughtState state = readState(process);
        assertThat(state.getStatus()).isEqualTo(DeepThoughtStatus.READY);
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

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
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

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
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
        process.getEngineParams().put(DeepThoughtEngine.MAX_RECOVERIES_KEY, 2);
        engine.start(process, ctx);
        // Every draft is broken — recoveryCount climbs 0→1→2 and the
        // engine bails into FAILED.
        chatModel.script(
                "```javascript\nfunction broken(\n```",
                "```javascript\nfunction still_broken(\n```",
                "```javascript\nfunction always_broken(\n```");

        drainTurns(process, 30);

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.FAILED);
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

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        assertThat(finalState.getRecoveryCount()).isEqualTo(1);
    }

    @Test
    void runTurns_withExecuteOnDone_passesThroughExecutingPhase() {
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(DeepThoughtEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");

        int turns = drainTurns(process, 10);

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        // READY → DRAFTING → VALIDATING → EXECUTING → DONE = 4.
        assertThat(turns).isEqualTo(4);
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
                DeepThoughtEngine.SCRIPT_ALLOWED_TOOLS_KEY,
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

    // ──────────────────── Idempotency ────────────────────

    @Test
    void terminalDoneStateIsIdempotentAcrossExtraTurns() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        chatModel.script("```javascript\n(function () { return 1; })();\n```");
        drainTurns(process, 10);

        DeepThoughtState before = readState(process);
        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);
        DeepThoughtState after = readState(process);

        assertThat(after.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        assertThat(after.getGeneratedCode()).isEqualTo(before.getGeneratedCode());
        // No extra LLM calls after DONE — chatModel queue stays at 0.
        assertThat(chatModel.remaining()).isZero();
    }

    @Test
    void terminalFailedStateIsIdempotent() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        DeepThoughtState s = readState(process);
        s.setStatus(DeepThoughtStatus.FAILED);
        s.setFailureReason("test-induced");
        engine.persistState(process, s);
        reset(eventEmitter);

        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);

        DeepThoughtState after = readState(process);
        assertThat(after.getStatus()).isEqualTo(DeepThoughtStatus.FAILED);
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
                .hasMessageContaining("requires a goal");
    }

    // ──────────────────── summarizeForParent ────────────────────

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
        DeepThoughtState s = readState(process);
        s.setStatus(DeepThoughtStatus.FAILED);
        s.setFailureReason("LLM returned 451");
        engine.persistState(process, s);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.FAILED);

        assertThat(report.humanSummary()).contains("LLM returned 451");
        assertThat(report.payload()).containsEntry("status", "FAILED");
    }

    // ──────────────────── Helpers ────────────────────

    private int drainTurns(ThinkProcessDocument process, int cap) {
        for (int i = 0; i < cap; i++) {
            DeepThoughtState before = readState(process);
            if (isTerminal(before.getStatus())) {
                engine.runTurn(process, ctx);
                return i;
            }
            engine.runTurn(process, ctx);
        }
        throw new AssertionError("turn cap exceeded — possible infinite loop");
    }

    private static boolean isTerminal(DeepThoughtStatus s) {
        return s == DeepThoughtStatus.DONE || s == DeepThoughtStatus.FAILED;
    }

    private DeepThoughtState readState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(DeepThoughtEngine.STATE_KEY);
        if (raw == null) return DeepThoughtState.builder().build();
        return objectMapper.convertValue(raw, DeepThoughtState.class);
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
