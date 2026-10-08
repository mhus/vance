package de.mhus.vance.addon.brain.nutrimat.cortado;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.brain.ai.AiChat;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * cortado's own loop: the stop is the loop's own decision, made through
 * the mandatory {@code loop_decide} tool — handled by the loop, never
 * dispatched to the tool layer.
 */
class NutrimatCortadoTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatCortado engine = new NutrimatCortado(
            h.thinkProcessService,
            h.objectMapper,
            h.streamingProperties,
            null,
            h.llmCallTracker,
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
            h.turnContextHandlers,
            null,
            h.notifications);

    private TurnOutcome run(LoopInputs in) {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    private static ToolExecutionRequest decideCall(String id, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(NutrimatCortado.LOOP_DECIDE)
                .arguments(arguments)
                .build();
    }

    private static AiMessage decide(boolean done, String reason, String answer) {
        return AiMessage.from(decideCall(
                "d1", "{\"done\": " + done + ", \"reason\": \"" + reason + "\", \"answer\": \"" + answer + "\"}"));
    }

    private static List<String> toolResults(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .toList();
    }

    @Test
    void done_endsTheTurn_andLoopDecideIsNeverDispatched() {
        h.script(toolCall("{\"n\":1}", ""), decide(true, "all modules listed", "api, shared, brain"));

        TurnOutcome out = run(h.inputs());

        assertThat(out.finalText()).isEqualTo("api, shared, brain");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        verify(h.tools, times(1)).invoke(eq("doc_read"), any());
        verify(h.tools, never()).invoke(eq(NutrimatCortado.LOOP_DECIDE), any());
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("roundReports", List.of("decision: done — all modules listed"));
    }

    @Test
    void notDone_keepsTheLoopGoing_withTheModelsOwnReason() {
        h.script(decide(false, "the brain module is missing", ""), decide(true, "complete now", "api, brain"));
        LoopInputs in = h.inputs();

        TurnOutcome out = run(in);

        assertThat(out.finalText()).isEqualTo("api, brain");
        assertThat(toolResults(in.messages())).contains("continue: the brain module is missing");
        assertThat(h.process.getEngineParams().get("nutrimatState"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry(
                        "roundReports",
                        List.of("decision: continue — the brain module is missing", "decision: done — complete now"));
    }

    @Test
    void endlessByDesign_noDecisionCap() {
        AiMessage[] script = new AiMessage[21];
        for (int i = 0; i < 20; i++) script[i] = decide(false, "not yet " + i, "");
        script[20] = decide(true, "finally", "the answer");
        h.script(script);

        assertThat(run(h.inputs()).finalText()).isEqualTo("the answer");
        assertThat(h.calls()).isEqualTo(21);
    }

    @Test
    void loopDecideNextToOtherTools_isRejected_theOtherToolsRun() {
        ToolExecutionRequest read = ToolExecutionRequest.builder()
                .id("t1")
                .name("doc_read")
                .arguments("{}")
                .build();
        AiMessage mixed = AiMessage.from(
                read, decideCall("d1", "{\"done\": true, \"reason\": \"early\", \"answer\": \"too early\"}"));
        h.script(mixed, decide(true, "now", "the answer"));
        LoopInputs in = h.inputs();

        TurnOutcome out = run(in);

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(toolResults(in.messages())).contains(NutrimatCortado.MIXED_REJECTION);
        verify(h.tools, times(1)).invoke(eq("doc_read"), any());
    }

    @Test
    void doneWithoutAnAnswer_isRejected_thenTheReasonIsTheFallbackReply() {
        h.script(decide(true, "r1", ""), decide(true, "r2", ""), decide(true, "the reason", ""));
        LoopInputs in = h.inputs();

        TurnOutcome out = run(in);

        assertThat(h.calls()).isEqualTo(NutrimatCortado.MAX_MISSING_ANSWER + 1);
        assertThat(out.finalText()).isEqualTo("the reason");
        assertThat(toolResults(in.messages())).contains(NutrimatCortado.ANSWER_REQUIRED);
    }

    @Test
    void invalidArguments_areRejected() {
        h.script(AiMessage.from(decideCall("d1", "{\"reason\": \"no done flag\"}")), decide(true, "ok", "42"));
        LoopInputs in = h.inputs();

        assertThat(run(in).finalText()).isEqualTo("42");
        assertThat(toolResults(in.messages())).contains(NutrimatCortado.INVALID_ARGUMENTS);
    }

    @Test
    void plainText_getsACorrection_thenTheRawTextIsAccepted() {
        h.script(text("just prose"), text("still prose"), text("prose again"));
        LoopInputs in = h.inputs();

        TurnOutcome out = run(in);

        assertThat(h.calls()).isEqualTo(NutrimatCortado.MAX_FORMAT_CORRECTIONS + 1);
        assertThat(out.finalText()).isEqualTo("prose again");
        assertThat(in.messages())
                .anyMatch(m -> m instanceof SystemMessage s && s.text().equals(NutrimatCortado.FORMAT_CORRECTION));
    }

    @Test
    void emptyReply_isAFailure() {
        h.script(text(""));

        TurnOutcome out = run(h.inputs());

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void loopDecide_isOnEveryRoundsToolSurface() {
        // An own scripted model that records the requests — the harness model
        // does not expose them.
        AiChat chat = mock(AiChat.class);
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(chat.streamingChatModel()).thenReturn(model);
        List<ChatRequest> requests = new ArrayList<>();
        doAnswer(inv -> {
                    requests.add(inv.getArgument(0));
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(decide(true, "done", "42"))
                            .build());
                    return null;
                })
                .when(model)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        LoopInputs base = h.inputs();
        LoopInputs in = new LoopInputs(
                chat,
                List.of(),
                base.tools(),
                new ArrayList<>(List.of(UserMessage.from("do the thing"))),
                false,
                "test-alias",
                "do the thing");

        assertThat(run(in).finalText()).isEqualTo("42");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).toolSpecifications())
                .extracting(ToolSpecification::name)
                .contains(NutrimatCortado.LOOP_DECIDE);
        assertThat(in.messages())
                .anyMatch(m -> m instanceof SystemMessage s && s.text().equals(NutrimatCortado.PROTOCOL));
    }

    @Test
    void roundSpecs_addLoopDecideToTheTurnsTools() {
        assertThat(NutrimatCortado.roundSpecs(List.of()))
                .extracting(ToolSpecification::name)
                .containsExactly(NutrimatCortado.LOOP_DECIDE);
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(() -> run(h.inputs())).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
        verify(h.tools, never()).invoke(anyString(), any());
    }
}
