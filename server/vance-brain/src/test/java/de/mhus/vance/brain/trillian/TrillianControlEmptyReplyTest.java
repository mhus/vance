package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Empty-reply handling in {@link TrillianControlEngine}. The engine adds
 * one retry of its own on top of the provider-level resilience layer, which
 * is right for a transient blank but wrong for an output-cap truncation —
 * that one reproduces exactly and costs another full token budget.
 */
class TrillianControlEmptyReplyTest {

    private static final String PROC_ID = "proc-trillian-control-1";

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private ScriptedStreamingChatModel chatModel;
    private TrillianControlEngine engine;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        chatModel = new ScriptedStreamingChatModel();

        AiChat aiChat = mock(AiChat.class);
        lenient().when(aiChat.streamingChatModel()).thenReturn(chatModel);
        EngineChatFactory engineChatFactory = mock(EngineChatFactory.class);
        lenient().when(engineChatFactory.forProcess(any(), any(), any()))
                .thenReturn(new EngineChatFactory.EngineChatBundle(
                        aiChat,
                        ChatBehavior.single(new AiChatConfig("test", "scripted", "stub-key"))));

        ContextToolsApi tools = mock(ContextToolsApi.class);
        lenient().when(tools.primaryAsLc4j()).thenReturn(List.of());

        EnginePromptResolver enginePromptResolver = mock(EnginePromptResolver.class);
        lenient().when(enginePromptResolver.resolve(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        SystemPromptComposer systemPromptComposer = mock(SystemPromptComposer.class);
        lenient().when(systemPromptComposer.compose(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        TrillianNature nature = mock(TrillianNature.class);
        lenient().when(nature.controlPromptAddendum(any())).thenReturn(null);
        TrillianNatureRegistry natureRegistry = mock(TrillianNatureRegistry.class);
        lenient().when(natureRegistry.resolve(any())).thenReturn(nature);

        ModelCatalog modelCatalog = mock(ModelCatalog.class);
        lenient().when(modelCatalog.lookupOrDefault(any(), any(), any(), any(), any()))
                .thenReturn(new ModelInfo(
                        "test", "test-model",
                        /*contextWindowTokens*/ 128_000,
                        /*defaultMaxOutputTokens*/ 4096,
                        ModelSize.LARGE,
                        java.util.Set.of(),
                        /*timeoutSeconds*/ 60,
                        /*actionLoopCorrections*/ 2,
                        /*stripThinkTags*/ false,
                        /*messageParser*/ null,
                        /*pricing*/ null,
                        OutputTokenParam.MAX_TOKENS,
                        java.util.Set.of(), null));

        MemoryContextLoader memoryContextLoader = mock(MemoryContextLoader.class);
        lenient().when(memoryContextLoader.composeBlock(any())).thenReturn(null);
        MemoryCompactionService memoryCompactionService = mock(MemoryCompactionService.class);
        lenient().when(memoryCompactionService.compactIfNeeded(any(), any(), any(), any()))
                .thenReturn(CompactionResult.noop("test"));

        engine = new TrillianControlEngine(
                thinkProcessService,
                mock(PromptDateContextResolver.class),
                mock(ScratchpadPromptContributor.class),
                engineChatFactory,
                mock(LlmCallTracker.class),
                new StreamingProperties(),
                JsonMapper.builder().build(),
                enginePromptResolver,
                systemPromptComposer,
                natureRegistry,
                modelCatalog,
                memoryContextLoader,
                memoryCompactionService);

        process = new ThinkProcessDocument();
        process.setId(PROC_ID);
        process.setTenantId("tenant-x");
        process.setSessionId("session-y");
        process.setProjectId("proj-1");
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
            doc.setId("msg-" + System.nanoTime());
            return doc;
        });
        lenient().when(thinkProcessService.findById(PROC_ID)).thenReturn(Optional.of(process));
    }

    @Test
    void emptyReply_withoutTruncation_isRetriedOnce() {
        chatModel.script(AiMessage.from(""));
        chatModel.script(AiMessage.from("Recovered on the second try."));

        engine.runTurn(process, ctx);

        assertThat(chatModel.callCount()).isEqualTo(2);
        verify(ctx).emitReply(eq("Recovered on the second try."), any(), any());
    }

    @Test
    void emptyReplyAtOutputCap_isNotRetried_andNamesTheTokenLimit() {
        chatModel.script(AiMessage.from(""));
        chatModel.finishReason(FinishReason.LENGTH);

        engine.runTurn(process, ctx);

        // The engine's own extra attempt is skipped: an identical request
        // hits the same cap for another full budget of output tokens.
        assertThat(chatModel.callCount()).isEqualTo(1);
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(ctx).emitReply(reply.capture(), any(), any());
        assertThat(reply.getValue())
                .contains("output-token limit")
                .contains("maxTokens")
                // "Rephrase the question" is the wrong advice here.
                .doesNotContain("Rephrase");
    }

    /**
     * Stub streaming model — delivers pre-scripted {@link AiMessage}s
     * synchronously, one per call, optionally stamped with a finish reason.
     */
    private static class ScriptedStreamingChatModel implements StreamingChatModel {
        private final java.util.Deque<AiMessage> queue = new java.util.ArrayDeque<>();
        private @org.jspecify.annotations.Nullable FinishReason finishReason;
        private int calls;

        void script(AiMessage msg) {
            queue.add(msg);
        }

        void finishReason(FinishReason reason) {
            this.finishReason = reason;
        }

        int callCount() {
            return calls;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls++;
            AiMessage msg = queue.poll();
            if (msg == null) {
                handler.onError(new IllegalStateException("no more scripted responses"));
                return;
            }
            ChatResponse.Builder builder = ChatResponse.builder().aiMessage(msg);
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            String text = msg.text();
            if (text != null && !text.isEmpty()) {
                handler.onPartialResponse(text);
            }
            handler.onCompleteResponse(builder.build());
        }
    }
}
