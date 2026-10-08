package de.mhus.vance.addon.brain.nutrimat.ristretto;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ristretto's own loop: the janx loop with its nets, plus a tool-less
 * reflection round after every {@code reflectEvery} tool rounds.
 */
class NutrimatRistrettoTest {

    private static final ToolSpecification DOC_READ =
            ToolSpecification.builder().name("doc_read").build();

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatRistretto engine = new NutrimatRistretto(
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

    private final LoopInputs in = h.inputs().withToolSpecs(List.of(DOC_READ));

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    private List<ChatRequest> requests() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(in.aiChat().streamingChatModel(), atLeastOnce())
                .chat(captor.capture(), any(StreamingChatResponseHandler.class));
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<String> reports() {
        Map<String, Object> state =
                (Map<String, Object>) h.process.getEngineParams().get("nutrimatState");
        return state == null ? List.of() : (List<String>) state.get("roundReports");
    }

    @Test
    void reflection_comesAfterExactlyNToolRounds_withoutTools() {
        h.process.getEngineParams().put(NutrimatRistretto.PARAM_REFLECT_EVERY, 2);
        h.script(
                toolCall("{\"n\":1}", ""),
                toolCall("{\"n\":2}", ""),
                text("worked: reading. next: answer"),
                text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
        List<ChatRequest> reqs = requests();
        assertThat(reqs).hasSize(4);
        assertThat(reqs.get(1).toolSpecifications()).containsExactly(DOC_READ);
        assertThat(reqs.get(2).toolSpecifications())
                .as("the reflection round runs without tools")
                .isNullOrEmpty();
        assertThat(reqs.get(3).toolSpecifications()).containsExactly(DOC_READ);
    }

    @Test
    void theReflection_staysInTheContext_andIsPublished() {
        h.process.getEngineParams().put(NutrimatRistretto.PARAM_REFLECT_EVERY, 1);
        h.script(toolCall("{\"n\":1}", ""), text("I should answer now"), text("the answer"));

        run();

        assertThat(in.messages())
                .anySatisfy(m -> assertThat(m)
                        .isInstanceOfSatisfying(
                                SystemMessage.class, s -> assertThat(s.text()).isEqualTo(NutrimatRistretto.REFLECT)))
                .anySatisfy(m -> assertThat(m)
                        .isInstanceOfSatisfying(
                                AiMessage.class, a -> assertThat(a.text()).isEqualTo("I should answer now")));
        assertThat(reports()).containsExactly("reflection after round 1: I should answer now");
    }

    @Test
    void reflectEveryZero_switchesTheReflectionOff() {
        h.process.getEngineParams().put(NutrimatRistretto.PARAM_REFLECT_EVERY, 0);
        h.script(
                toolCall("{\"n\":1}", ""),
                toolCall("{\"n\":2}", ""),
                toolCall("{\"n\":3}", ""),
                toolCall("{\"n\":4}", ""),
                text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(h.calls()).isEqualTo(5);
        assertThat(reports()).isEmpty();
    }

    @Test
    void aStuckModel_meetsAReflection_beforeTheIdleStuckNetFires() {
        // Default reflectEvery (3) sits below the idle-stuck threshold (5).
        h.script(
                toolCall("{\"same\":true}", ""),
                toolCall("{\"same\":true}", ""),
                toolCall("{\"same\":true}", ""),
                text("I keep reading the same doc"),
                toolCall("{\"same\":true}", ""));

        TurnOutcome out = run();

        assertThat(reports()).containsExactly("reflection after round 3: I keep reading the same doc");
        assertThat(out.recovered()).as("the idle-stuck net still ends the turn").isTrue();
        assertThat(out.finalText()).contains("repeated the same tool call");
        // 3 tool rounds + 1 reflection + 2 tool rounds — the 5th identical batch trips the net.
        assertThat(h.calls()).isEqualTo(6);
    }

    @Test
    void aSafetyStop_closesAWorkerWithTaskFailed() {
        h.process.setParentProcessId("parent");
        h.process.getEngineParams().put(NutrimatRistretto.PARAM_REFLECT_EVERY, 0);
        h.script(toolCall("{\"same\":true}", ""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).startsWith("⚠️ TASK FAILED");
    }

    @Test
    void anEmptyReply_isASafetyStop() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void theInterrupt_stopsBeforeTheNextCall() {
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);
        h.script(text("never"));

        assertThatThrownBy(this::run).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
    }
}
