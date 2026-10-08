package de.mhus.vance.addon.brain.nutrimat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopState;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.StopDecision;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.api.notification.NotificationSeverity;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the loop kernel with a scripted streaming model — the working-log
 * semantics live here, not in the hooks: an intermediate round's text is
 * persisted as an interim chat message exactly when the loop is known to go
 * on (tool calls dispatched, judge pushed back), and the round whose text
 * becomes the reply is never persisted by the kernel (the shell commits it).
 */
class NutrimatKernelLoopTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ChatMessageService chatLog = mock(ChatMessageService.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final ClientEventPublisher events = mock(ClientEventPublisher.class);
    private final StreamingProperties streamingProperties = mock(StreamingProperties.class);
    private final LlmCallTracker llmCallTracker = mock(LlmCallTracker.class);
    private final TurnContextHandlerRegistry turnContextHandlers = mock(TurnContextHandlerRegistry.class);
    private final ContextToolsApi tools = mock(ContextToolsApi.class);
    private final AiChat aiChat = mock(AiChat.class);
    private final StreamingChatModel streamingModel = mock(StreamingChatModel.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final ThinkProcessDocument process = new ThinkProcessDocument();
    private final List<ChatResponse> script = new ArrayList<>();
    private final AtomicInteger scriptIdx = new AtomicInteger();

    @BeforeEach
    void setUp() {
        process.setId("p1");
        process.setTenantId("acme");
        process.setSessionId("s1");
        process.setStatus(ThinkProcessStatus.RUNNING);

        when(ctx.chatMessageService()).thenReturn(chatLog);
        when(ctx.events()).thenReturn(events);
        when(aiChat.streamingChatModel()).thenReturn(streamingModel);
        when(streamingProperties.getChunkCharThreshold()).thenReturn(100_000);
        when(streamingProperties.getChunkFlushMs()).thenReturn(60_000L);
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        when(thinkProcessService.isHaltRequested("p1")).thenReturn(false);
        when(turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(tools.invoke(anyString(), any())).thenReturn(Map.of("ok", true));
        // Scripted model: each chat() call completes with the next response.
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onCompleteResponse(script.get(scriptIdx.getAndIncrement()));
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    // Positional constructor args on purpose — a signature change must break
    // the compile here. The kernel touches the service, the tracker, the
    // stream plumbing and the turn-context handlers; everything else stays
    // null.
    private AbstractNutrimat engine(Function<AiMessage, StopDecision> stopPolicy) {
        return engine(stopPolicy, 0, null);
    }

    /**
     * The anonymous test nature: {@code budget} drives iterationBudget
     * (0 = uncapped), {@code reportText} makes the stop hook send one report
     * through the report channel — the two nature policies under test.
     */
    private AbstractNutrimat engine(Function<AiMessage, StopDecision> stopPolicy, int budget, String reportText) {
        return new AbstractNutrimat(
                thinkProcessService,
                new ObjectMapper(),
                streamingProperties,
                null,
                llmCallTracker,
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
                notifications) {
            @Override
            protected String natureId() {
                return "janx";
            }

            @Override
            protected String loopType() {
                return "kernel test loop";
            }

            @Override
            protected int iterationBudget(ThinkProcessDocument process) {
                return budget;
            }

            @Override
            protected StopDecision onNaturalStopCandidate(LoopState state, AiMessage reply) {
                if (reportText != null) {
                    report(state, reportText);
                }
                return stopPolicy.apply(reply);
            }
        };
    }

    private static LoopInputs inputs(AiChat chat, ContextToolsApi toolApi) {
        return new LoopInputs(
                chat,
                List.of(),
                toolApi,
                new ArrayList<ChatMessage>(List.of(UserMessage.from("build the thing"))),
                false,
                "test-alias",
                "build the thing");
    }

    private static AiMessage toolRound(String text) {
        return AiMessage.builder()
                .text(text)
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("t1")
                        .name("doc_list")
                        .arguments("{}")
                        .build()))
                .build();
    }

    private static ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    private List<ChatMessageDocument> appended() {
        ArgumentCaptor<ChatMessageDocument> saved = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, atLeastOnce()).append(saved.capture());
        return saved.getAllValues();
    }

    private static List<String> contents(List<ChatMessageDocument> docs) {
        return docs.stream().map(ChatMessageDocument::getContent).toList();
    }

    @Test
    void toolRound_persistsWorkingLog_theFinalRoundNeverDoes() {
        script.add(response(toolRound("round one text")));
        script.add(response(AiMessage.from("the final answer")));

        TurnOutcome outcome =
                engine(a -> StopDecision.accept()).runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        assertThat(outcome.finalText()).isEqualTo("the final answer");
        List<ChatMessageDocument> docs = appended();
        // The intermediate tool round's text lands in the working log once,
        // as an interim message; the kernel never persists the text that
        // becomes the reply — the shell commits that as the turn answer.
        List<ChatMessageDocument> roundTexts = docs.stream()
                .filter(d -> "round one text".equals(d.getContent()))
                .toList();
        assertThat(roundTexts).hasSize(1);
        assertThat(roundTexts.get(0).isInterim()).isTrue();
        assertThat(contents(docs)).doesNotContain("the final answer");
    }

    @Test
    void judgeContinue_persistsTheDraftBeforeTheDecisionNote() {
        script.add(response(AiMessage.from("draft text")));
        script.add(response(AiMessage.from("final answer")));
        AtomicInteger stops = new AtomicInteger();
        AbstractNutrimat engine = engine(
                a -> stops.incrementAndGet() == 1 ? StopDecision.continueLoop("not done yet") : StopDecision.accept());

        engine.runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // A pushed-back stop candidate is intermediate by definition: its
        // text enters the working log, and it does so BEFORE the decision
        // note so the transcript keeps the round's text above the note.
        List<String> contents = contents(appended());
        int draftIdx = contents.indexOf("draft text");
        int noteIdx = contents.indexOf("[janx] stop decision: continue — not done yet");
        assertThat(draftIdx).isGreaterThanOrEqualTo(0);
        assertThat(noteIdx).isGreaterThan(draftIdx);
    }

    @Test
    void report_recordsIntoThePersistedLoopState() {
        script.add(response(AiMessage.from("the final answer")));

        engine(a -> StopDecision.accept(), 0, "I read the pom.xml and listed the modules")
                .runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // The report channel is base mechanics: the nature's report lands in
        // nutrimatState.roundReports — persisted live (appendRoundReport), not
        // deferred to the turn end.
        assertThat(process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", java.util.List.of("I read the pom.xml and listed the modules"));
    }

    @Test
    void report_publishesTheReportToTheSession() {
        script.add(response(AiMessage.from("the final answer")));

        engine(a -> StopDecision.accept(), 0, "I read the pom.xml and listed the modules")
                .runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // Recorded AND pinged: the notify side-channel carries the report to
        // the session's clients.
        verify(notifications).publish(process, "I read the pom.xml and listed the modules", NotificationSeverity.INFO);
    }

    @Test
    void report_truncatesLongReportsForThePing() {
        script.add(response(AiMessage.from("final answer")));

        engine(a -> StopDecision.accept(), 0, "x".repeat(200))
                .runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // The notification is a short ping (≤120 chars recommended); the
        // recorded report keeps the full text.
        verify(notifications).publish(process, "x".repeat(120) + "…", NotificationSeverity.INFO);
    }

    @Test
    void report_hiddenProcessRecordsButStaysSilent() {
        process.setHiddenFromUi(true);
        script.add(response(AiMessage.from("final answer")));

        engine(a -> StopDecision.accept(), 0, "I worked silently")
                .runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // Same audience guard as the loop narration: hidden processes record
        // their reports but do not ping the session.
        assertThat(process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", java.util.List.of("I worked silently"));
        verify(notifications, never()).publish(any(), anyString(), any());
    }

    @Test
    void exhausted_persistsEveryIntermediateRound() {
        script.add(response(toolRound("r1 text")));
        script.add(response(toolRound("r2 text is the longer one")));

        TurnOutcome outcome = engine(a -> StopDecision.accept(), 2, null)
                .runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        List<String> contents = contents(appended());
        assertThat(contents).contains("r1 text", "r2 text is the longer one");
        // janx's exhausted policy carries the best partial work out as the
        // recovered text — that is the outcome, not a second working-log row.
        assertThat(outcome.recovered()).isTrue();
    }

    @Test
    void loopNarration_defaultIsTheBareCounter_noteWordingIsNatureOwned() {
        script.add(response(AiMessage.from("the final answer")));

        engine(a -> StopDecision.accept()).runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        // The base class carries no budget vocabulary — the plain counter is
        // the default; redbull/clubmate override with their budget wording.
        assertThat(contents(appended())).contains("[janx] round 1");
    }

    @Test
    void loopNarration_roundsMode_suppressesDecisionsButKeepsRoundNotes() {
        process.setEngineParams(Map.of("loopNarration", "rounds"));
        script.add(response(AiMessage.from("the final answer")));

        engine(a -> StopDecision.accept()).runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        List<String> contents = contents(appended());
        // Round notes pass …
        assertThat(contents).contains("[janx] round 1");
        // … decision notes are exactly the noise the mode suppresses.
        assertThat(contents).noneMatch(c -> c.contains("stop decision"));
    }

    @Test
    void loopNarration_offMode_emitsNoNotes() {
        process.setEngineParams(Map.of("loopNarration", "off"));
        script.add(response(toolRound("round one text")));
        script.add(response(AiMessage.from("the final answer")));

        engine(a -> StopDecision.accept()).runLoop(process, ctx, inputs(aiChat, tools), new LoopStats());

        List<String> contents = contents(appended());
        assertThat(contents).noneMatch(c -> c.startsWith("[janx]"));
        // The working log is independent of the narration knob.
        assertThat(contents).contains("round one text");
    }
}
