package de.mhus.vance.addon.brain.nutrimat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the nature loop tests: a scripted streaming model (one
 * scripted reply per {@code round()}, the last one repeated), the mocks the
 * loop primitives touch, and a recorder of everything appended to the chat
 * log. A nature under test is constructed with {@link #thinkProcessService},
 * {@link #objectMapper}, {@link #streamingProperties}, {@link #llmCallTracker},
 * {@link #turnContextHandlers} and {@link #notifications}; everything else
 * stays {@code null}.
 */
public final class NutrimatLoopHarness {

    public final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    public final ObjectMapper objectMapper = new ObjectMapper();
    public final StreamingProperties streamingProperties = mock(StreamingProperties.class);
    public final LlmCallTracker llmCallTracker = mock(LlmCallTracker.class);
    public final TurnContextHandlerRegistry turnContextHandlers = mock(TurnContextHandlerRegistry.class);
    public final NotificationService notifications = mock(NotificationService.class);
    public final ChatMessageService chatLog = mock(ChatMessageService.class);
    public final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    public final ContextToolsApi tools = mock(ContextToolsApi.class);
    public final ThinkProcessDocument process = new ThinkProcessDocument();
    public final List<ChatMessageDocument> appended = new ArrayList<>();

    private final AiChat aiChat = mock(AiChat.class);
    private final StreamingChatModel streamingModel = mock(StreamingChatModel.class);
    private final List<AiMessage> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int failAtCall = -1;
    private @Nullable RuntimeException failure;

    public NutrimatLoopHarness() {
        process.setId("p1");
        process.setTenantId("acme");
        process.setSessionId("s1");
        process.setStatus(ThinkProcessStatus.RUNNING);
        process.setEngineParams(new LinkedHashMap<>());

        lenient().when(ctx.chatMessageService()).thenReturn(chatLog);
        lenient().when(ctx.events()).thenReturn(mock(ClientEventPublisher.class));
        lenient().when(aiChat.streamingChatModel()).thenReturn(streamingModel);
        lenient().when(streamingProperties.getChunkCharThreshold()).thenReturn(100_000);
        lenient().when(streamingProperties.getChunkFlushMs()).thenReturn(60_000L);
        lenient().when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        lenient().when(turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tools.invoke(anyString(), any())).thenReturn(Map.of("ok", true));
        lenient().when(chatLog.append(any(ChatMessageDocument.class))).thenAnswer(inv -> {
            appended.add(inv.getArgument(0));
            return null;
        });
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    int call = calls.incrementAndGet();
                    if (call == failAtCall && failure != null) {
                        handler.onError(failure);
                        return null;
                    }
                    AiMessage msg = script.get(Math.min(call - 1, script.size() - 1));
                    handler.onCompleteResponse(
                            ChatResponse.builder().aiMessage(msg).build());
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    /** Scripts the model: one reply per round, the last one repeated forever. */
    public NutrimatLoopHarness script(AiMessage... replies) {
        script.addAll(List.of(replies));
        return this;
    }

    /** The {@code call}-th model call (1-based) fails with {@code error}. */
    public NutrimatLoopHarness failAt(int call, RuntimeException error) {
        this.failAtCall = call;
        this.failure = error;
        return this;
    }

    public int calls() {
        return calls.get();
    }

    public LoopInputs inputs() {
        return new LoopInputs(
                aiChat,
                List.of(),
                tools,
                new ArrayList<ChatMessage>(List.of(UserMessage.from("do the thing"))),
                false,
                "test-alias",
                "do the thing");
    }

    public List<String> contents() {
        return appended.stream().map(ChatMessageDocument::getContent).toList();
    }

    public static AiMessage text(String text) {
        return AiMessage.from(text);
    }

    /** A tool round; distinct {@code args} keep it clear of any idle-stuck net. */
    public static AiMessage toolCall(String args, String text) {
        AiMessage.Builder b = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("t1")
                        .name("doc_read")
                        .arguments(args)
                        .build()));
        if (!text.isEmpty()) b.text(text);
        return b.build();
    }
}
