package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.OutputTokenParam;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both Trillian engines have to read the two interrupt channels every
 * other engine reads.
 *
 * <p>The one that matters is the out-of-band halt flag. ESC / {@code
 * /pause} / {@code //trillian stop} set it immediately and queue the
 * {@code PAUSED} write as a lane task — which cannot run while the turn
 * being interrupted holds that lane. An engine that only looks at the
 * status therefore finishes its whole turn first (up to 12 iterations in
 * Control, 24 in the user-loop) and the human sees ESC do nothing.
 *
 * <p>The second test in each pair covers the iteration cap of the
 * user-loop: falling out of it used to drop the task without a word.
 */
class TrillianEngineInterruptTest {

    private static final String PROC_ID = "proc-1";
    private static final String PEER_ID = "peer-1";
    private static final String TASK_ID = "task-42";

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private ScriptedStreamingChatModel chatModel;
    private ContextToolsApi tools;
    private TrillianWakeupService wakeupService;
    private TrillianInternalApi trillianApi;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        chatModel = new ScriptedStreamingChatModel();
        wakeupService = mock(TrillianWakeupService.class);
        trillianApi = mock(TrillianInternalApi.class);

        tools = mock(ContextToolsApi.class);
        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());
        lenient().when(tools.invoke(any(), any())).thenReturn(Map.of("ok", true));

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setCreatedAt(Instant.now());

        ctx = mock(ThinkEngineContext.class);
        lenient().when(ctx.chatMessageService()).thenReturn(chatMessageService);
        lenient().when(ctx.tools()).thenReturn(tools);
        lenient().when(ctx.drainPending()).thenReturn(List.of());
        lenient().when(ctx.historyTagSink()).thenReturn(mock(BufferingHistoryTagSink.class));
        lenient().when(ctx.events()).thenReturn(mock(ClientEventPublisher.class));
        lenient().when(chatMessageService.activeHistory(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatMessageService.append(any())).thenAnswer(inv -> {
            ChatMessageDocument doc = inv.getArgument(0);
            doc.setId("msg-1");
            return doc;
        });
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    // ──────────────────── Control ────────────────────

    @Test
    void control_haltRequested_stopsBeforeTheFirstModelCall() {
        when(thinkProcessService.isHaltRequested(PROC_ID)).thenReturn(true);

        control().runTurn(process, ctx);

        assertThat(chatModel.callCount()).isZero();
        verify(thinkProcessService).clearHalt(PROC_ID);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.PAUSED);
        verify(thinkProcessService, never()).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    @Test
    void control_pausedStatus_stopsAndLeavesTheStatusAlone() {
        // The pause handler already wrote PAUSED; writing IDLE on the way
        // out would undo the user's interrupt.
        process.setStatus(ThinkProcessStatus.PAUSED);

        control().runTurn(process, ctx);

        assertThat(chatModel.callCount()).isZero();
        verify(thinkProcessService, never()).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    // ──────────────────── User loop ────────────────────

    @Test
    void userLoop_haltRequested_stopsBeforeTheFirstModelCall() {
        when(thinkProcessService.isHaltRequested(PROC_ID)).thenReturn(true);

        userLoop().runTurn(process, ctx);

        assertThat(chatModel.callCount()).isZero();
        verify(thinkProcessService).clearHalt(PROC_ID);
        verify(thinkProcessService).updateStatus(PROC_ID, ThinkProcessStatus.PAUSED);
    }

    @Test
    void userLoop_pausedStatus_stopsAndLeavesTheStatusAlone() {
        process.setStatus(ThinkProcessStatus.PAUSED);

        userLoop().runTurn(process, ctx);

        assertThat(chatModel.callCount()).isZero();
        verify(thinkProcessService, never()).updateStatus(PROC_ID, ThinkProcessStatus.IDLE);
    }

    @Test
    void userLoop_runningOutOfIterations_reportsTheTaskItWasGiven() {
        // The human saw "Queued (taskId=…)". Before this the loop logged a
        // warning, went IDLE, and the task stayed open for good: Control
        // hears nothing, and the self-check cannot fill the gap either —
        // its findings come from spawned workers, and this task never got
        // one.
        when(ctx.drainPending()).thenReturn(List.of(taskRequest(TASK_ID)));
        when(trillianApi.findPeer(PROC_ID)).thenReturn(Optional.of(peer()));
        chatModel.alwaysReply(toolCall("current_time"));

        userLoop().runTurn(process, ctx);

        verify(trillianApi).dispatchTaskEvent(
                eq(PROC_ID), eq(PEER_ID),
                eq(TrillianInternalApi.TASK_EVENT_FAILED), eq(TASK_ID), any(), any());
    }

    @Test
    void userLoop_aTaskItAlreadyReported_isNotFailedTwice() {
        // Reporting it and then contradicting that report with a generic
        // failure would be worse than saying nothing.
        when(ctx.drainPending()).thenReturn(List.of(taskRequest(TASK_ID)));
        chatModel.alwaysReply(toolCall("task_complete", "{\"taskId\":\"" + TASK_ID + "\"}"));

        userLoop().runTurn(process, ctx);

        verify(trillianApi, never()).dispatchTaskEvent(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void openTaskIds_readsOnlyTaskRequests() {
        List<SteerMessage> drained = List.of(
                taskRequest("t-1"),
                new SteerMessage.ProcessEvent(
                        Instant.now(), null, "src", ProcessEventType.SUMMARY, "done",
                        Map.of(TrillianInternalApi.PAYLOAD_KEY_TASK_EVENT,
                                        TrillianInternalApi.TASK_EVENT_DONE,
                                TrillianInternalApi.PAYLOAD_KEY_TASK_ID, "t-2"),
                        null, null),
                new SteerMessage.ProcessEvent(
                        Instant.now(), null, "src", ProcessEventType.SUMMARY, "no payload",
                        null, null, null));

        assertThat(TrillianUserEngine.openTaskIds(drained)).containsExactly("t-1");
    }

    // ──────────────────── harness ────────────────────

    private TrillianControlEngine control() {
        return new TrillianControlEngine(
                thinkProcessService,
                mock(PromptDateContextResolver.class),
                mock(ScratchpadPromptContributor.class),
                engineChatFactory(),
                mock(LlmCallTracker.class),
                new StreamingProperties(),
                JsonMapper.builder().build(),
                promptResolver(),
                promptComposer(),
                natureRegistry(),
                modelCatalog(),
                memoryContextLoader(),
                compactionService());
    }

    private TrillianUserEngine userLoop() {
        return new TrillianUserEngine(
                thinkProcessService,
                mock(PromptDateContextResolver.class),
                mock(ScratchpadPromptContributor.class),
                engineChatFactory(),
                mock(LlmCallTracker.class),
                new StreamingProperties(),
                JsonMapper.builder().build(),
                promptResolver(),
                promptComposer(),
                natureRegistry(),
                wakeupService,
                trillianApi,
                modelCatalog(),
                memoryContextLoader(),
                compactionService());
    }

    private EngineChatFactory engineChatFactory() {
        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory factory = mock(EngineChatFactory.class);
        lenient().when(factory.forProcess(any(), any(), any()))
                .thenReturn(new EngineChatFactory.EngineChatBundle(
                        aiChat,
                        ChatBehavior.single(new AiChatConfig("test", "scripted", "stub-key"))));
        return factory;
    }

    private EnginePromptResolver promptResolver() {
        EnginePromptResolver resolver = mock(EnginePromptResolver.class);
        lenient().when(resolver.resolve(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        return resolver;
    }

    private SystemPromptComposer promptComposer() {
        SystemPromptComposer composer = mock(SystemPromptComposer.class);
        lenient().when(composer.compose(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        return composer;
    }

    private TrillianNatureRegistry natureRegistry() {
        TrillianNature nature = mock(TrillianNature.class);
        TrillianNatureRegistry registry = mock(TrillianNatureRegistry.class);
        lenient().when(registry.resolve(any())).thenReturn(nature);
        return registry;
    }

    private ModelCatalog modelCatalog() {
        ModelCatalog catalog = mock(ModelCatalog.class);
        lenient().when(catalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(new ModelInfo(
                        "test", "test-model", 128_000, 4096, ModelSize.LARGE,
                        java.util.Set.of(), 60, 2, false, null, null,
                        OutputTokenParam.MAX_TOKENS, java.util.Set.of(), null));
        return catalog;
    }

    private MemoryContextLoader memoryContextLoader() {
        MemoryContextLoader loader = mock(MemoryContextLoader.class);
        lenient().when(loader.composeBlock(any())).thenReturn(null);
        return loader;
    }

    private MemoryCompactionService compactionService() {
        MemoryCompactionService service = mock(MemoryCompactionService.class);
        lenient().when(service.compactIfNeeded(any(), any(), any(), any()))
                .thenReturn(CompactionResult.noop("test"));
        return service;
    }

    private static ThinkProcessDocument peer() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(PEER_ID);
        p.setTenantId("acme");
        return p;
    }

    private static SteerMessage taskRequest(String taskId) {
        return new SteerMessage.ProcessEvent(
                Instant.now(), null, "control-proc", ProcessEventType.SUMMARY,
                "Task request: do the thing",
                Map.of(TrillianInternalApi.PAYLOAD_KEY_TASK_EVENT,
                                TrillianInternalApi.TASK_EVENT_REQUEST,
                        TrillianInternalApi.PAYLOAD_KEY_TASK_ID, taskId),
                null, null);
    }

    private static AiMessage toolCall(String name) {
        return toolCall(name, "{}");
    }

    private static AiMessage toolCall(String name, String arguments) {
        return AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1").name(name).arguments(arguments).build()))
                .build();
    }

    /** Replays one scripted message per call, or the same one forever. */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private @org.jspecify.annotations.Nullable AiMessage repeated;
        private int calls;

        void alwaysReply(AiMessage msg) {
            this.repeated = msg;
        }

        int callCount() {
            return calls;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls++;
            if (repeated == null) {
                handler.onError(new IllegalStateException("no scripted response"));
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(repeated).build());
        }
    }
}
