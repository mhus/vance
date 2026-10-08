package de.mhus.vance.addon.brain.nutrimat.janx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.brain.tools.ContextToolsApi;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * janx owns its loop: the Ford worker loop driven by a scripted streaming
 * model. Natural stop ends the turn with no routine round cap; the nets end
 * it with a stop text — a worker's says "TASK FAILED" (the shell closes it
 * INCOMPLETE), a chat's offers "continue"; the interrupt comes from
 * {@code round()} and is never swallowed.
 */
class NutrimatJanxLoopTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ChatMessageService chatLog = mock(ChatMessageService.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final StreamingProperties streamingProperties = mock(StreamingProperties.class);
    private final TurnContextHandlerRegistry turnContextHandlers = mock(TurnContextHandlerRegistry.class);
    private final ContextToolsApi tools = mock(ContextToolsApi.class);
    private final AiChat aiChat = mock(AiChat.class);
    private final StreamingChatModel streamingModel = mock(StreamingChatModel.class);

    private final ThinkProcessDocument process = new ThinkProcessDocument();
    private final List<AiMessage> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    // Positional args on purpose — a constructor change must break compile.
    // The loop touches the service, the tracker, the stream plumbing and the
    // turn-context handlers; everything else stays null.
    private final NutrimatJanx janx = new NutrimatJanx(
            thinkProcessService,
            new ObjectMapper(),
            streamingProperties,
            null,
            mock(LlmCallTracker.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            turnContextHandlers,
            null,
            null);

    @BeforeEach
    void setUp() {
        process.setId("p1");
        process.setTenantId("acme");
        process.setSessionId("s1");
        process.setStatus(ThinkProcessStatus.RUNNING);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(JanxSafetyNet.PARAM_POLL_THROTTLE_STEP_MS, 0); // no real sleeping
        process.setEngineParams(params);

        when(ctx.chatMessageService()).thenReturn(chatLog);
        when(ctx.events()).thenReturn(mock(ClientEventPublisher.class));
        when(aiChat.streamingChatModel()).thenReturn(streamingModel);
        when(streamingProperties.getChunkCharThreshold()).thenReturn(100_000);
        when(streamingProperties.getChunkFlushMs()).thenReturn(60_000L);
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        when(turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(tools.invoke(anyString(), any())).thenReturn(Map.of("ok", true));
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    int i = calls.getAndIncrement();
                    AiMessage msg = script.get(Math.min(i, script.size() - 1));
                    handler.onCompleteResponse(
                            ChatResponse.builder().aiMessage(msg).build());
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private TurnOutcome run() {
        return janx.runLoop(
                process,
                ctx,
                new LoopInputs(
                        aiChat,
                        List.of(),
                        tools,
                        new ArrayList<ChatMessage>(List.of(UserMessage.from("do the thing"))),
                        false,
                        "test-alias",
                        "do the thing"),
                new LoopStats());
    }

    private static AiMessage toolCall(String args, String text) {
        AiMessage.Builder b = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("t1")
                        .name("doc_read")
                        .arguments(args)
                        .build()));
        if (!text.isEmpty()) b.text(text);
        return b.build();
    }

    @Test
    void naturalStop_afterManyRounds_isTheAnswer_noRoutineCap() {
        for (int i = 0; i < 60; i++) script.add(toolCall("{\"n\":" + i + "}", ""));
        script.add(AiMessage.from("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        // An answer leaves the process IDLE in both modes.
        assertThat(out.awaitingUserInput()).isFalse();
        assertThat(out.recovered()).isFalse();
        assertThat(calls.get()).isEqualTo(61);
    }

    @Test
    void idleStuck_worker_endsWithTaskFailed() {
        process.setParentProcessId("parent");
        script.add(toolCall("{\"path\":\"a.md\"}", "reading a.md"));

        TurnOutcome out = run();

        assertThat(out.recovered())
                .as("hard failure → the shell closes the worker INCOMPLETE")
                .isTrue();
        assertThat(out.finalText())
                .startsWith("⚠️ TASK FAILED")
                .contains("repeated the same tool call")
                .contains("Partial progress:\n\nreading a.md");
        assertThat(calls.get()).isEqualTo(JanxSafetyNet.DEFAULT_IDLE_STUCK_THRESHOLD);
    }

    @Test
    void idleStuck_chat_endsWithAContinuableStopText() {
        script.add(toolCall("{\"path\":\"a.md\"}", ""));

        TurnOutcome out = run();

        assertThat(out.awaitingUserInput()).as("a chat parks BLOCKED").isTrue();
        assertThat(out.finalText()).startsWith("⚠️ I stopped this turn").contains("\"continue\"");
    }

    @Test
    void statusPolls_areExemptFromIdleStuck() {
        AiMessage poll = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("t1")
                        .name("exec_status")
                        .arguments("{\"id\":\"job\"}")
                        .build()))
                .build();
        for (int i = 0; i < 8; i++) script.add(poll);
        script.add(AiMessage.from("build green"));

        assertThat(run().finalText()).isEqualTo("build green");
    }

    @Test
    void emptyReply_isAStopText_neverASilentEnd() {
        process.setParentProcessId("parent");
        script.add(AiMessage.builder().text("").build());

        TurnOutcome out = run();

        assertThat(out.finalText()).startsWith("⚠️ TASK FAILED").contains("empty response");
    }

    @Test
    void modelFailure_isAStopText_carryingTheProgress() {
        script.add(toolCall("{}", "half done"));
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    if (calls.getAndIncrement() == 0) {
                        handler.onCompleteResponse(
                                ChatResponse.builder().aiMessage(script.get(0)).build());
                    } else {
                        handler.onError(new IllegalStateException("model exploded"));
                    }
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        TurnOutcome out = run();

        assertThat(out.finalText()).contains("model exploded").contains("Progress so far:\n\nhalf done");
    }

    @Test
    void maxIterations_isOptIn() {
        process.getEngineParams().put(JanxSafetyNet.PARAM_MAX_ITERATIONS, 2);
        script.add(toolCall("{\"n\":1}", ""));
        script.add(toolCall("{\"n\":2}", ""));

        TurnOutcome out = run();

        assertThat(out.finalText()).contains("step limit (2 rounds (maxIterations))");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void wallclock_zeroMinutes_tripsBeforeTheFirstCall() {
        process.getEngineParams().put(JanxSafetyNet.PARAM_MAX_WALLCLOCK_MINUTES, 0);
        script.add(AiMessage.from("never reached"));

        assertThat(run().finalText()).contains("per-turn time limit (0 minutes)");
        assertThat(calls.get()).isZero();
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        script.add(AiMessage.from("never reached"));
        when(thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(this::run)
                .isInstanceOf(NutrimatInterruptedException.class)
                .satisfies(e -> assertThat(((NutrimatInterruptedException) e).forcePause())
                        .isTrue());
        assertThat(calls.get()).isZero();
    }

    @Test
    void validation_correctsAThinReplyAfterBigToolData() {
        when(tools.invoke(anyString(), any())).thenReturn(Map.of("data", "x".repeat(600)));
        script.add(toolCall("{}", ""));
        script.add(AiMessage.from("ok"));
        String relayed = "the full data relayed: " + "x".repeat(250);
        script.add(AiMessage.from(relayed));

        TurnOutcome out = janx.runLoop(
                process,
                ctx,
                new LoopInputs(
                        aiChat,
                        List.of(),
                        tools,
                        new ArrayList<ChatMessage>(List.of(UserMessage.from("read it"))),
                        true,
                        "test-alias",
                        "read it"),
                new LoopStats());

        // One correction, then the substantive reply is accepted.
        assertThat(out.finalText()).isEqualTo(relayed);
        assertThat(calls.get()).isEqualTo(3);
    }
}
