package de.mhus.vance.brain.ford;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.brain.thinkengine.loop.EngineLoopProperties;
import de.mhus.vance.brain.thinkengine.loop.LoopLimits;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ford's tool loop as a clean worker: natural stop ends the turn, there is
 * no routine round cap, and every safety net (idle-stuck, empty reply, LLM
 * failure, wallclock, opt-in {@code maxIterations}) ends the turn with a
 * stop text — a worker then closes {@code INCOMPLETE}, a primary (chat)
 * parks {@code BLOCKED} and can be continued. Driven by a scripted
 * streaming model instead of a live LLM.
 */
class FordLoopTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final ChatMessageService chatLog = mock(ChatMessageService.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final ContextToolsApi tools = mock(ContextToolsApi.class);
    private final ScriptedStreamingChatModel chatModel = new ScriptedStreamingChatModel();
    private final TurnContextHandlerRegistry turnContextHandlers = mock(TurnContextHandlerRegistry.class);
    private final EngineLoopProperties properties = new EngineLoopProperties();
    private final List<ChatMessageDocument> appended = new ArrayList<>();
    private Ford ford;

    @BeforeEach
    void setUp() {
        properties.setPollThrottleStepMs(0); // no real sleeping in tests
        lenient().when(turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());
        lenient().when(tools.primary()).thenReturn(java.util.Set.of());
        lenient().when(tools.activePromptHints()).thenReturn(List.of());
        lenient().when(tools.withAdditional(any())).thenReturn(tools);
        lenient().when(tools.invoke(anyString(), any())).thenReturn(Map.of("ok", true));
        when(ctx.chatMessageService()).thenReturn(chatLog);
        when(ctx.tools()).thenReturn(tools);
        lenient().when(ctx.events()).thenReturn(mock(de.mhus.vance.brain.events.ClientEventPublisher.class));
        lenient()
                .when(ctx.historyTagSink())
                .thenReturn(mock(de.mhus.vance.brain.history.BufferingHistoryTagSink.class));
        lenient().when(chatLog.activeHistory(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatLog.append(any(ChatMessageDocument.class))).thenAnswer(inv -> {
            appended.add(inv.getArgument(0));
            return null;
        });
        lenient().when(processes.findById(anyString())).thenAnswer(inv -> Optional.of(process()));
        ford = new Ford(
                processes,
                JsonMapper.builder().build(),
                new StreamingProperties(),
                modelCatalog(),
                mock(LlmCallTracker.class),
                mock(MemoryContextLoader.class),
                promptResolver(),
                composer(),
                engineChatFactory(),
                mock(MemoryService.class),
                compactionService(),
                mock(SkillResolver.class),
                mock(SkillPromptComposer.class),
                mock(SkillTriggerMatcher.class),
                mock(SessionService.class),
                mock(PromptDateContextResolver.class),
                mock(ScratchpadPromptContributor.class),
                mock(WorkspaceService.class),
                historyStrengthFilter(),
                clientTurnContextResolver(),
                turnContextHandlers,
                mock(ShootyGuardService.class),
                properties);
    }

    // ── the loop ─────────────────────────────────────────────────────

    @Test
    void naturalStop_afterManyToolRounds_isAnAnswer_noRoutineCap() {
        // 60 distinct tool rounds — far above the old 40 cap — then an answer.
        AiMessage[] script = new AiMessage[61];
        for (int i = 0; i < 60; i++) script[i] = toolCall("doc_read", "{\"n\":" + i + "}", "");
        script[60] = text("the answer");
        chatModel.script(script);

        ford.steer(worker(), ctx, said("go"));

        assertThat(chatModel.callCount()).isEqualTo(61);
        assertThat(lastAssistantText()).isEqualTo("the answer");
        verify(ctx).emitReply(eq("the answer"), any(), eq(null));
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.IDLE);
        verify(processes, never()).closeProcess(anyString(), any());
    }

    @Test
    void naturalStop_primary_goesIdle_blockedIsReservedForObstacles() {
        chatModel.script(toolCall("doc_read", "{}", ""), text("the answer"));

        ford.steer(primary(), ctx, said("go"));

        assertThat(lastAssistantText()).isEqualTo("the answer");
        // An answer is "done, ready for the next message" — BLOCKED would read
        // as a question (Magrathea needs_input, Arthur auto-forward).
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.IDLE);
        verify(processes, never()).updateStatus("proc-1", ThinkProcessStatus.BLOCKED);
    }

    @Test
    void idleStuck_worker_closesIncompleteWithStopText() {
        chatModel.script(toolCall("doc_read", "{\"path\":\"a.md\"}", "reading a.md"));

        ford.steer(worker(), ctx, said("go"));

        assertThat(chatModel.callCount()).isEqualTo(properties.getIdleStuckThreshold());
        assertThat(lastAssistantText())
                .startsWith("⚠️ TASK FAILED")
                .contains("repeated the same tool call")
                .contains("Partial progress:\n\nreading a.md");
        verify(ctx).emitReply(argThat(t -> t.startsWith("⚠️ TASK FAILED")), any(), eq(null));
        verify(processes).closeProcess("proc-1", CloseReason.INCOMPLETE);
    }

    @Test
    void idleStuck_primary_parksBlockedAndInvitesContinue() {
        chatModel.script(toolCall("doc_read", "{\"path\":\"a.md\"}", ""));

        ford.steer(primary(), ctx, said("go"));

        assertThat(lastAssistantText()).startsWith("⚠️ I stopped this turn").contains("\"continue\"");
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.BLOCKED);
        verify(processes, never()).closeProcess(anyString(), any());
    }

    @Test
    void statusPolling_isExemptFromIdleStuck() {
        AiMessage poll = toolCall("exec_status", "{\"id\":\"job-1\"}", "");
        chatModel.script(poll, poll, poll, poll, poll, poll, poll, text("build green"));

        ford.steer(worker(), ctx, said("build it"));

        assertThat(lastAssistantText()).isEqualTo("build green");
        verify(processes, never()).closeProcess(anyString(), any());
    }

    @Test
    void emptyReply_isASafetyStop_neverASilentEnd() {
        chatModel.script(AiMessage.builder().text("").build());

        ford.steer(worker(), ctx, said("go"));

        assertThat(lastAssistantText()).startsWith("⚠️ TASK FAILED").contains("empty response");
        verify(ctx).emitReply(argThat(t -> t.contains("empty response")), any(), eq(null));
        verify(processes).closeProcess("proc-1", CloseReason.INCOMPLETE);
    }

    @Test
    void llmFailureWithoutText_stillProducesAStopText() {
        chatModel.script(text("never reached"));
        chatModel.failAt(1, new IllegalStateException("model exploded"));

        ford.steer(worker(), ctx, said("go"));

        assertThat(lastAssistantText()).startsWith("⚠️ TASK FAILED").contains("model exploded");
        verify(processes).closeProcess("proc-1", CloseReason.INCOMPLETE);
    }

    @Test
    void llmFailure_primary_carriesTheProgressAndParksBlocked() {
        chatModel.script(toolCall("doc_read", "{}", "half of the report"), text("never reached"));
        chatModel.failAt(2, new IllegalStateException("model exploded"));

        ford.steer(primary(), ctx, said("go"));

        assertThat(lastAssistantText()).contains("Progress so far:\n\nhalf of the report");
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.BLOCKED);
    }

    @Test
    void wallclock_zeroMinutes_tripsBeforeTheFirstCall() {
        ThinkProcessDocument p = worker();
        p.getEngineParams().put(LoopLimits.PARAM_MAX_WALLCLOCK_MINUTES, 0);
        chatModel.script(text("never reached"));

        ford.steer(p, ctx, said("go"));

        assertThat(chatModel.callCount()).isZero();
        assertThat(lastAssistantText()).contains("per-turn time limit (0 minutes)");
        verify(processes).closeProcess("proc-1", CloseReason.INCOMPLETE);
    }

    @Test
    void maxIterations_isOptIn_andEndsWithAStopText() {
        ThinkProcessDocument p = worker();
        p.getEngineParams().put(LoopLimits.PARAM_MAX_ITERATIONS, 2);
        chatModel.script(toolCall("doc_read", "{\"n\":1}", ""), toolCall("doc_read", "{\"n\":2}", ""));

        ford.steer(p, ctx, said("go"));

        assertThat(chatModel.callCount()).isEqualTo(2);
        assertThat(lastAssistantText()).contains("step limit (2 rounds (maxIterations))");
        verify(processes).closeProcess("proc-1", CloseReason.INCOMPLETE);
    }

    @Test
    void haltRequested_interruptsWithoutAnAnswer() {
        chatModel.script(text("never reached"));
        when(processes.isHaltRequested("proc-1")).thenReturn(true);

        ford.steer(primary(), ctx, said("go"));

        assertThat(chatModel.callCount()).isZero();
        verify(ctx, never()).emitReply(any(), any(), any());
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.PAUSED);
    }

    @Test
    void runTurn_yieldsAtTheDrainHeadWhenHaltIsRequested() {
        when(processes.isHaltRequested("proc-1")).thenReturn(true);

        ford.runTurn(primary(), ctx);

        verify(ctx, never()).drainPending();
    }

    @Test
    void runTurn_stopsDrainingAfterTheWorkerClosed() {
        chatModel.script(AiMessage.builder().text("").build());
        when(ctx.drainPending()).thenReturn(List.of(said("go")), List.of(said("more")));
        when(processes.findById(anyString())).thenAnswer(inv -> {
            ThinkProcessDocument p = worker();
            p.setStatus(ThinkProcessStatus.CLOSED);
            return Optional.of(p);
        });

        ford.runTurn(worker(), ctx);

        // The first turn hit the status check at the loop head (CLOSED →
        // interrupt); the drain loop must not pull the second batch.
        verify(ctx, org.mockito.Mockito.times(1)).drainPending();
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        p.setTenantId("t");
        p.setProjectId("p");
        p.setSessionId("s");
        p.setStatus(ThinkProcessStatus.RUNNING);
        p.setEngineParams(new LinkedHashMap<>());
        return p;
    }

    private ThinkProcessDocument primary() {
        return process();
    }

    private ThinkProcessDocument worker() {
        ThinkProcessDocument p = process();
        p.setParentProcessId("parent-1");
        return p;
    }

    private static SteerMessage.UserChatInput said(String content) {
        return new SteerMessage.UserChatInput(Instant.now(), null, "mara", content);
    }

    private @Nullable String lastAssistantText() {
        for (int i = appended.size() - 1; i >= 0; i--) {
            if (appended.get(i).getRole() == de.mhus.vance.api.chat.ChatRole.ASSISTANT) {
                return appended.get(i).getContent();
            }
        }
        return null;
    }

    private static AiMessage text(String text) {
        return AiMessage.builder().text(text).build();
    }

    private static AiMessage toolCall(String name, String args, String text) {
        AiMessage.Builder b = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name(name)
                        .arguments(args)
                        .build()));
        if (!text.isEmpty()) b.text(text);
        return b.build();
    }

    private ModelCatalog modelCatalog() {
        ModelCatalog catalog = mock(ModelCatalog.class);
        lenient()
                .when(catalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(new ModelInfo(
                        "test",
                        "test-model",
                        128_000,
                        4096,
                        ModelSize.LARGE,
                        java.util.Set.of(),
                        60,
                        2,
                        false,
                        null,
                        null,
                        null,
                        java.util.Set.of(),
                        null));
        return catalog;
    }

    private EnginePromptResolver promptResolver() {
        EnginePromptResolver resolver = mock(EnginePromptResolver.class);
        lenient().when(resolver.resolve(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        return resolver;
    }

    private SystemPromptComposer composer() {
        SystemPromptComposer composer = mock(SystemPromptComposer.class);
        lenient().when(composer.compose(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        return composer;
    }

    private EngineChatFactory engineChatFactory() {
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory factory = mock(EngineChatFactory.class);
        lenient()
                .when(factory.forProcess(any(), any(), any()))
                .thenReturn(new EngineChatFactory.EngineChatBundle(
                        aiChat,
                        de.mhus.vance.brain.ai.ChatBehavior.single(new AiChatConfig("test", "scripted", "stub-key"))));
        return factory;
    }

    private MemoryCompactionService compactionService() {
        MemoryCompactionService service = mock(MemoryCompactionService.class);
        lenient().when(service.compactIfNeeded(any(), any(), any(), any())).thenReturn(CompactionResult.noop("test"));
        return service;
    }

    private ClientTurnContextResolver clientTurnContextResolver() {
        ClientTurnContextResolver resolver = mock(ClientTurnContextResolver.class);
        lenient()
                .when(resolver.resolve(any(), any()))
                .thenReturn(mock(ClientTurnContextResolver.ClientTurnContext.class));
        return resolver;
    }

    private HistoryStrengthFilter historyStrengthFilter() {
        HistoryStrengthFilter filter = mock(HistoryStrengthFilter.class);
        lenient().when(filter.filter(any())).thenReturn(List.of());
        return filter;
    }

    /** Replays one scripted message per call, in order, then the last one forever. */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private final List<AiMessage> script = new ArrayList<>();
        private @Nullable AiMessage repeated;
        private int calls;
        private int failAtCall = -1;
        private @Nullable RuntimeException failError;

        void script(AiMessage... messages) {
            script.addAll(List.of(messages));
            repeated = messages[messages.length - 1];
        }

        void failAt(int call, RuntimeException error) {
            this.failAtCall = call;
            this.failError = error;
        }

        int callCount() {
            return calls;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls++;
            if (calls == failAtCall && failError != null) {
                handler.onError(failError);
                return;
            }
            AiMessage msg = calls <= script.size() ? script.get(calls - 1) : repeated;
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(msg).build());
        }
    }
}
