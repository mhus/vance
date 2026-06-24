package de.mhus.vance.brain.lunkwill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loop-level tests for {@link LunkwillEngine}. Drives the engine with
 * a scripted {@link StreamingChatModel} so the four stop paths
 * (natural stop, tool-driven terminate, external interrupt, safety
 * nets) can be pinned down without a real LLM.
 */
class LunkwillEngineSkeletonTest {

    private static final String PROC_ID = "proc-lunkwill-1";

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private EngineChatFactory engineChatFactory;
    private LlmCallTracker llmCallTracker;
    private ContextToolsApi tools;
    private BufferingHistoryTagSink tagSink;
    private ScriptedStreamingChatModel chatModel;
    private ObjectMapper objectMapper;
    private EnginePromptResolver enginePromptResolver;
    private SystemPromptComposer systemPromptComposer;
    private SkillResolver skillResolver;
    private SkillPromptComposer skillPromptComposer;
    private SessionService sessionService;

    private LunkwillEngine engine;
    private LunkwillProperties properties;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        engineChatFactory = mock(EngineChatFactory.class);
        llmCallTracker = mock(LlmCallTracker.class);
        tools = mock(ContextToolsApi.class);
        tagSink = mock(BufferingHistoryTagSink.class);
        objectMapper = JsonMapper.builder().build();
        properties = new LunkwillProperties();

        StreamingProperties streaming = new StreamingProperties();
        chatModel = new ScriptedStreamingChatModel();

        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);

        AiChatConfig cfg = new AiChatConfig("test", "scripted", "stub-key");
        ChatBehavior behavior = ChatBehavior.single(cfg);
        EngineChatFactory.EngineChatBundle bundle =
                new EngineChatFactory.EngineChatBundle(aiChat, behavior);
        lenient().when(engineChatFactory.forProcess(any(), any(), any())).thenReturn(bundle);

        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());
        // Skills add no extra tools by default — the per-turn allow-set
        // stays untouched. `withAdditional(empty)` returns `this`.
        lenient().when(tools.withAdditional(any())).thenReturn(tools);

        enginePromptResolver = mock(EnginePromptResolver.class);
        systemPromptComposer = mock(SystemPromptComposer.class);
        lenient().when(enginePromptResolver.resolve(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(systemPromptComposer.compose(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        skillResolver = mock(SkillResolver.class);
        skillPromptComposer = mock(SkillPromptComposer.class);
        sessionService = mock(SessionService.class);
        lenient().when(skillPromptComposer.mergedTools(any()))
                .thenReturn(java.util.Set.of());
        lenient().when(skillPromptComposer.compose(any(), any())).thenReturn(null);
        lenient().when(sessionService.findBySessionId(any())).thenReturn(Optional.empty());

        de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader =
                mock(de.mhus.vance.brain.memory.MemoryContextLoader.class);
        lenient().when(memoryContextLoader.composeBlock(any())).thenReturn(null);

        de.mhus.vance.brain.ai.ModelCatalog modelCatalog =
                mock(de.mhus.vance.brain.ai.ModelCatalog.class);
        de.mhus.vance.brain.ai.ModelInfo fakeModelInfo = new de.mhus.vance.brain.ai.ModelInfo(
                "test", "test-model",
                /*contextWindowTokens*/ 128_000,
                /*defaultMaxOutputTokens*/ 4096,
                de.mhus.vance.brain.ai.ModelSize.LARGE,
                java.util.Set.of(),
                /*timeoutSeconds*/ 60,
                /*actionLoopCorrections*/ 2,
                /*stripThinkTags*/ false);
        lenient().when(modelCatalog.lookupOrDefault(
                        any(), any(), any(), any(), any()))
                .thenReturn(fakeModelInfo);
        de.mhus.vance.brain.memory.MemoryCompactionService memoryCompactionService =
                mock(de.mhus.vance.brain.memory.MemoryCompactionService.class);
        lenient().when(memoryCompactionService.compactIfNeeded(any(), any(), any(), any()))
                .thenReturn(de.mhus.vance.brain.memory.CompactionResult.noop("test"));

        engine = new LunkwillEngine(
                thinkProcessService, properties, engineChatFactory,
                llmCallTracker, streaming, objectMapper,
                enginePromptResolver, systemPromptComposer,
                skillResolver, skillPromptComposer, sessionService,
                memoryContextLoader,
                modelCatalog, memoryCompactionService);

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("tenant-x");
        process.setSessionId("session-y");
        process.setProjectId("proj-1");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setCreatedAt(Instant.now());

        ctx = mock(ThinkEngineContext.class);
        ClientEventPublisher events = mock(ClientEventPublisher.class);
        lenient().when(ctx.chatMessageService()).thenReturn(chatMessageService);
        lenient().when(ctx.tools()).thenReturn(tools);
        lenient().when(ctx.drainPending()).thenReturn(List.of());
        lenient().when(ctx.historyTagSink()).thenReturn(tagSink);
        lenient().when(ctx.events()).thenReturn(events);
        lenient().when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatMessageService.append(any())).thenAnswer(inv -> {
            ChatMessageDocument doc = inv.getArgument(0);
            doc.setId("msg-" + System.nanoTime());
            return doc;
        });
        // Default status read: same as snapshot.
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    // ─── Stop path 1: natural stop (always IDLE, both modes) ────────────

    @Test
    void naturalStop_emitsAssistantMessageAndStaysIdle() {
        chatModel.script(AiMessage.from("Done. Renamed two methods."));

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.RUNNING);
        // Context stays alive — exits IDLE, not CLOSED.
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        // Final assistant message persisted to chat log.
        verify(chatMessageService).append(any());
        // Reply emitted to parent / progress channel.
        verify(ctx).emitReply(eq("Done. Renamed two methods."), any(), any());
    }

    // ─── Stop path 1b: empty LLM response (model collapse) ──────────────

    @Test
    void emptyLlmResponse_persistsErrorMessageAndBlocks() {
        // Gemini-style collapse: no text, no tool calls, finish=STOP.
        // langchain4j AiMessage rejects null text — empty string
        // exercises the same engine branch (text().isBlank() == true,
        // hasToolExecutionRequests() == false).
        chatModel.script(AiMessage.from(""));

        engine.runTurn(process, ctx);

        // The standard natural-stop path would have silently dropped the turn
        // (no chat append, status IDLE). The empty-response branch instead:
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        // Assistant message persisted so the user sees the worker bailed.
        verify(chatMessageService).append(any());
        // Reply emitted so a parent (worker mode) or UI (session-primary)
        // sees the error too.
        verify(ctx).emitReply(org.mockito.ArgumentMatchers.contains("leere Antwort"), any(), any());
    }

    // ─── Stop path 2: tool-driven terminate (mode-aware) ────────────────

    @Test
    void toolTerminate_workerMode_closesDoneAfterBatch() {
        process.setParentProcessId("parent-arthur-1");
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("task_complete")
                .arguments("{\"summary\":\"all done\"}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("task_complete"), any()))
                .thenReturn(java.util.Map.of(
                        "summary", "all done",
                        LunkwillTermination.RESULT_TERMINATE_KEY, true));

        engine.runTurn(process, ctx);

        verify(tools).invoke(eq("task_complete"), any());
        // Worker: explicit "done forever" → process is closed.
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.DONE);
    }

    @Test
    void toolTerminate_sessionPrimaryMode_staysIdle() {
        // No parent → session-primary.
        process.setParentProcessId(null);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("task_complete")
                .arguments("{\"summary\":\"all done\"}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("task_complete"), any()))
                .thenReturn(java.util.Map.of(
                        "summary", "all done",
                        LunkwillTermination.RESULT_TERMINATE_KEY, true));

        engine.runTurn(process, ctx);

        verify(tools).invoke(eq("task_complete"), any());
        // Session-primary: signal received but session stays open.
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    // ─── Stop path 3: external interrupt ────────────────────────────────

    @Test
    void externalInterrupt_suspendedBeforeFirstIteration_exitsWithoutLlmCall() {
        ThinkProcessDocument current = new ThinkProcessDocument();
        current.setId(PROC_ID);
        current.setStatus(ThinkProcessStatus.SUSPENDED);
        when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(current));

        engine.runTurn(process, ctx);

        // No LLM call should have happened — the ChatModel script is empty,
        // so any call would throw.
        assertThat(chatModel.callCount()).isEqualTo(0);
        // Status was set to RUNNING once at entry; the loop exits without a closeProcess.
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.RUNNING);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    @Test
    void externalInterrupt_closedMidLoop_exitsAfterStatusChange() {
        // First iter: LLM asks for a tool call. Before second iter, status flips to CLOSED.
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("noop_tool")
                .arguments("{}")
                .build();
        chatModel.script(AiMessage.from("", List.of(call)));

        when(tools.invoke(eq("noop_tool"), any())).thenAnswer(inv -> {
            // After this tool runs, flip the persisted status to CLOSED.
            ThinkProcessDocument closed = new ThinkProcessDocument();
            closed.setId(PROC_ID);
            closed.setStatus(ThinkProcessStatus.CLOSED);
            when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(closed));
            return java.util.Map.of("ok", true);
        });

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(1);
        // No closeProcess call from the engine — the process was already closed externally.
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Stop path 4a: wallclock safety net ─────────────────────────────

    @Test
    void wallclockExceeded_setsBlocked() {
        properties.setMaxWallclockMinutes(0);  // anything > 0 ms past createdAt trips it
        process.setCreatedAt(Instant.now().minusSeconds(60));

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(0);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Stop path 4b: idle-stuck safety net ────────────────────────────

    @Test
    void idleStuck_sameToolRepeated_setsBlocked() {
        properties.setIdleStuckThreshold(2);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-X")
                .name("stuck_tool")
                .arguments("{\"path\":\"X\"}")
                .build();
        // Scripted to always return the same tool call.
        chatModel.scriptRepeating(AiMessage.from("", List.of(call)));
        when(tools.invoke(eq("stuck_tool"), any())).thenReturn(java.util.Map.of("ok", true));

        engine.runTurn(process, ctx);

        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.BLOCKED);
        verify(thinkProcessService, never()).closeProcess(eq(PROC_ID), any());
    }

    // ─── Metadata + lifecycle smoke ─────────────────────────────────────

    @Test
    void metadata_returnsExpectedValues() {
        assertThat(engine.name()).isEqualTo("lunkwill");
        assertThat(engine.title()).contains("Lunkwill");
        assertThat(engine.version()).isEqualTo("0.5.0");
        assertThat(engine.description()).isNotBlank();
    }

    @Test
    void stop_closesProcessWithStoppedReason() {
        engine.stop(process, ctx);
        verify(thinkProcessService).closeProcess(PROC_ID, CloseReason.STOPPED);
    }

    @Test
    void terminationConventionKey_isStable() {
        assertThat(LunkwillTermination.RESULT_TERMINATE_KEY).isEqualTo("_terminate");
    }

    @Test
    void allowedTools_engineBaselineSetExposed() {
        // Lunkwill returns a non-empty engine-default set so the resolver
        // can compute (engineDefault ∪ recipe.add) ∖ recipe.remove instead
        // of falling through to "no engine-level restriction" (which would
        // dump the full tenant tool buffet into every LLM call).
        var set = engine.allowedTools();
        assertThat(set).isNotEmpty();
        // Discovery + intro essentials
        assertThat(set).contains("find_tools", "describe_tool", "how_do_i",
                "manual_read", "tool_result_read");
        // Sub-worker spawn — Lunkwill's escape hatch
        assertThat(set).contains("process_create");
        // User-facing signal
        assertThat(set).contains("vance_notify");
        // Generic work-target file / exec wrappers + work_target_get/set
        assertThat(set).contains("file_read", "file_write", "file_edit",
                "file_list", "file_find", "file_grep", "file_head_tail",
                "file_count", "exec_run", "exec_status", "exec_tail",
                "exec_kill", "work_target_get", "work_target_set");
        // Plan-tracking pair (reduced Plan-Mode variant, §9)
        assertThat(set).contains("todo_write", "todo_update");
    }

    // ──────────────────── ScriptedStreamingChatModel ─────────────────────

    /**
     * Minimal stub streaming model — delivers a pre-scripted
     * {@link AiMessage} synchronously via the handler's
     * {@code onCompleteResponse}. Either a single-shot script (consumed
     * on first call, exhausts after) or a repeating one (every call
     * gets the same answer — used for idle-stuck testing).
     */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private final Deque<AiMessage> queue = new ArrayDeque<>();
        private @org.jspecify.annotations.Nullable AiMessage repeating;
        private int calls;

        void script(AiMessage msg) {
            queue.add(msg);
        }

        void scriptRepeating(AiMessage msg) {
            this.repeating = msg;
        }

        int callCount() {
            return calls;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls++;
            AiMessage msg;
            if (repeating != null) {
                msg = repeating;
            } else if (!queue.isEmpty()) {
                msg = queue.poll();
            } else {
                handler.onError(new IllegalStateException(
                        "ScriptedStreamingChatModel: no more scripted responses"));
                return;
            }
            ChatResponse response = ChatResponse.builder().aiMessage(msg).build();
            String text = msg.text();
            if (text != null && !text.isEmpty()) {
                handler.onPartialResponse(text);
            }
            handler.onCompleteResponse(response);
        }
    }
}
