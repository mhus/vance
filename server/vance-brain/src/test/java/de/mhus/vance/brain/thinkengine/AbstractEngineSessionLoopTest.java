package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
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
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared control-loop kernel of {@link AbstractEngineSessionLoop}
 * (planning/session-loop-extraction.md, decision F5) — tested once here
 * instead of three times indirectly: exit-status mapping (chat form
 * awaits, worker form goes idle), the status/halt interrupts, the
 * best-free-text recovery, and the iteration backstop. The per-engine
 * splitInbox tests keep the F3 contract; the engine session tests keep
 * the forks.
 */
class AbstractEngineSessionLoopTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final ChatMessageService chatLog = mock(ChatMessageService.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final ScriptedStreamingChatModel chatModel = new ScriptedStreamingChatModel();
    private final TurnContextHandlerRegistry turnContextHandlers = mock(TurnContextHandlerRegistry.class);
    private final TestLoop loop = new TestLoop();

    // ── fixtures ─────────────────────────────────────────────────────

    /** Minimal concrete loop: everything shared is the base under test. */
    private class TestLoop extends AbstractEngineSessionLoop {
        TestLoop() {
            super(
                    processes,
                    JsonMapper.builder().build(),
                    new StreamingProperties(),
                    modelCatalog(),
                    mock(LlmCallTracker.class),
                    memoryContextLoader(),
                    promptResolver(),
                    composer(),
                    engineChatFactory(),
                    mock(MemoryService.class),
                    compactionService(),
                    mock(PromptDateContextResolver.class),
                    mock(ScratchpadPromptContributor.class),
                    clientTurnContextResolver(),
                    turnContextHandlers,
                    mock(ShootyGuardService.class),
                    mock(WorkspaceService.class),
                    historyStrengthFilter());
        }

        @Override
        protected String engineName() {
            return "test";
        }

        @Override
        protected String fallbackSystemPrompt() {
            return "sys";
        }

        @Override
        protected String defaultPromptPath() {
            return "prompts/test.md";
        }

        @Override
        protected String statusBlock(ThinkProcessDocument process) {
            return "STATUS-BLOCK";
        }
    }

    @BeforeEach
    void setUp() {
        // Build the tools mock BEFORE entering when(...): stubbing inside an
        // unfinished when() argument is the classic UnfinishedStubbing trap.
        // Turn-context handlers pass the messages through untouched.
        lenient().when(turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        ContextToolsApi toolsApi = tools();
        when(ctx.chatMessageService()).thenReturn(chatLog);
        when(ctx.tools()).thenReturn(toolsApi);
        when(ctx.events()).thenReturn(mock(de.mhus.vance.brain.events.ClientEventPublisher.class));
        when(ctx.historyTagSink()).thenReturn(mock(de.mhus.vance.brain.history.BufferingHistoryTagSink.class));
        when(processes.findById(anyString())).thenAnswer(inv -> Optional.of(process()));
        lenient().when(chatLog.activeHistory(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatLog.append(any(ChatMessageDocument.class))).thenReturn(null);
    }

    private ThinkProcessDocument process() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        p.setTenantId("t");
        p.setProjectId("p");
        p.setSessionId("s");
        p.setStatus(ThinkProcessStatus.IDLE);
        p.setEngineParams(new LinkedHashMap<>());
        return p;
    }

    private ThinkProcessDocument chatProcess() {
        return process(); // no parent — chat form
    }

    private ThinkProcessDocument workerProcess() {
        ThinkProcessDocument p = process();
        p.setParentProcessId("parent-1");
        return p;
    }

    private static SteerMessage.UserChatInput said(String content) {
        return new SteerMessage.UserChatInput(Instant.now(), null, "mara", content);
    }

    private ContextToolsApi tools() {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());
        lenient().when(tools.primary()).thenReturn(java.util.Set.of());
        lenient().when(tools.discoveryBlockMarkdown()).thenReturn("");
        lenient().when(tools.demotedDiscoveryBlockMarkdown()).thenReturn("");
        lenient().when(tools.activePromptHints()).thenReturn(List.of());
        return tools;
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

    private MemoryContextLoader memoryContextLoader() {
        MemoryContextLoader loader = mock(MemoryContextLoader.class);
        lenient().when(loader.composeBlock(any())).thenReturn(null);
        return loader;
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

    private static AiMessage text(String text) {
        return AiMessage.builder().text(text).build();
    }

    private static AiMessage toolCall(String name) {
        return AiMessage.builder()
                .text("thinking out loud")
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name(name)
                        .arguments("{}")
                        .build()))
                .build();
    }

    /** Replays one scripted message per call, in order, then the last one forever. */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private final List<AiMessage> script = new java.util.ArrayList<>();
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

    // ── the kernel ───────────────────────────────────────────────────

    @Test
    void plainTurn_persistsAndEmitsTheReply_chatFormParksBlocked() {
        chatModel.script(text("here is your answer"));
        ThinkProcessDocument p = chatProcess();

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(p, ctx, List.of(said("how is it going?")));

        assertThat(out.finalText()).isEqualTo("here is your answer");
        assertThat(out.awaitingUserInput()).isTrue(); // chat form awaits its user
        assertThat(out.interrupted()).isFalse();
        // The user's message AND the reply land in the chat log exactly once.
        ArgumentCaptor<ChatMessageDocument> saved = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(2)).append(saved.capture());
        assertThat(saved.getAllValues().get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(saved.getAllValues().get(0).getContent()).isEqualTo("how is it going?");
        assertThat(saved.getAllValues().get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(saved.getAllValues().get(1).getContent()).isEqualTo("here is your answer");
        verify(ctx).emitReply(eq("here is your answer"), any(), eq(null));
        // Status: RUNNING at turn start, BLOCKED as chat-form exit.
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.RUNNING);
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.BLOCKED);
    }

    @Test
    void steeredWorker_answersAndGoesIdle() {
        chatModel.script(text("the report"));

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(workerProcess(), ctx, List.of(said("go")));

        assertThat(out.awaitingUserInput()).isFalse(); // answers the parent, back to idle
        verify(processes).updateStatus("proc-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void suspendedMidLoop_interruptsWithoutSurfacingAnAnswer() {
        chatModel.script(toolCall("doc_read"));
        when(processes.findById(anyString())).thenAnswer(inv -> {
            ThinkProcessDocument p = process();
            p.setStatus(ThinkProcessStatus.SUSPENDED);
            return Optional.of(p);
        });

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(chatProcess(), ctx, List.of(said("go")));

        assertThat(out.interrupted()).isTrue();
        assertThat(out.interruptForcePause()).isFalse();
        assertThat(out.finalText()).isEmpty();
        verify(ctx.historyTagSink()).discard();
        verify(ctx, never()).emitReply(any(), any(), any());
        // No BLOCKED/IDLE exit write — the pause handling owns the status.
        verify(processes, times(1)).updateStatus(any(), any());
    }

    @Test
    void haltRequested_interruptsWithForcePause() {
        chatModel.script(toolCall("doc_read"));
        when(processes.isHaltRequested("proc-1")).thenReturn(true);

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(chatProcess(), ctx, List.of(said("go")));

        assertThat(out.interrupted()).isTrue();
        assertThat(out.interruptForcePause()).isTrue();
    }

    @Test
    void llmFailureAfterPartialText_recoversWithTheBestFreeText() {
        chatModel.script(toolCall("doc_read"), text("never reached"));
        chatModel.failAt(2, new IllegalStateException("model exploded"));

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(chatProcess(), ctx, List.of(said("go")));

        assertThat(out.finalText()).isEqualTo("thinking out loud");
        assertThat(out.awaitingUserInput()).isTrue();
        verify(ctx).emitReply(eq("thinking out loud"), any(), any());
    }

    @Test
    void iterationBackstop_throwsWhenNothingRecoverable() {
        ThinkProcessDocument p = chatProcess();
        p.setEngineParams(new LinkedHashMap<>(Map.of("maxIterations", 2)));
        // Tool-call replies without text: nothing recoverable.
        AiMessage silentToolCall = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("doc_read")
                        .arguments("{}")
                        .build()))
                .build();
        chatModel.script(silentToolCall, silentToolCall);

        assertThatThrownBy(() -> loop.turnFor(p, ctx, List.of(said("go"))))
                .isInstanceOf(de.mhus.vance.brain.ai.AiChatException.class)
                .hasMessageContaining("exceeded 2 tool iterations");
        assertThat(chatModel.callCount()).isEqualTo(2);
    }

    @Test
    void iterationBackstop_recoversWithTextWhenTheLoopRanHot() {
        ThinkProcessDocument p = chatProcess();
        p.setEngineParams(new LinkedHashMap<>(Map.of("maxIterations", 2)));
        AiMessage chatty = toolCall("doc_read"); // carries "thinking out loud"
        chatModel.script(chatty, chatty);

        AbstractEngineSessionLoop.TurnOutcome out = loop.turnFor(p, ctx, List.of(said("go")));

        assertThat(out.finalText()).isEqualTo("thinking out loud");
        assertThat(chatModel.callCount()).isEqualTo(2);
    }
}
