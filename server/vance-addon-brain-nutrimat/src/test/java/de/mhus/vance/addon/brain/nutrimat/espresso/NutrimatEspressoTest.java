package de.mhus.vance.addon.brain.nutrimat.espresso;

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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * espresso's own loop: a tool-less planning round whose plan stays in the
 * context and is published, then natural-stop execution against the plan.
 */
class NutrimatEspressoTest {

    private static final ToolSpecification DOC_READ =
            ToolSpecification.builder().name("doc_read").build();

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatEspresso engine = new NutrimatEspresso(
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
    void planningRound_hasNoTools_executionRoundsDo() {
        h.script(text("1. read the doc\n2. answer"), toolCall("{\"n\":1}", "STEP 1: reading"), text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        List<ChatRequest> reqs = requests();
        assertThat(reqs).hasSize(3);
        assertThat(reqs.get(0).toolSpecifications())
                .as("the planning round runs without tools")
                .isNullOrEmpty();
        assertThat(reqs.get(1).toolSpecifications()).containsExactly(DOC_READ);
    }

    @Test
    void thePlan_staysInTheContext_andIsPublished() {
        h.script(text("1. read the doc\n2. answer"), text("STEP 2: the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("STEP 2: the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
        List<ChatMessage> messages = in.messages();
        assertThat(messages)
                .anySatisfy(m -> assertThat(m)
                        .isInstanceOfSatisfying(
                                AiMessage.class, a -> assertThat(a.text()).isEqualTo("1. read the doc\n2. answer")));
        assertThat(reports()).containsExactly("plan: 1. read the doc\n2. answer");
    }

    @Test
    void aRevisedPlan_isPublished() {
        h.script(
                text("1. read\n2. answer"),
                toolCall("{\"n\":1}", "PLAN REVISED: 1. search\n2. answer"),
                text("the answer"));

        run();

        assertThat(reports()).containsExactly("plan: 1. read\n2. answer", "plan revised: 1. search\n2. answer");
    }

    @Test
    void anEmptyPlan_failsTheTurn() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty plan");
        assertThat(h.calls()).isEqualTo(1);
    }

    @Test
    void anEmptyExecutionReply_failsTheTurn() {
        h.script(text("1. answer"), text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void theOptInRoundCap_carriesTheBestTextOut() {
        h.process.getEngineParams().put("maxIterations", 3);
        h.script(text("1. loop"), toolCall("{\"n\":1}", "STEP 1: partial progress"), toolCall("{\"n\":2}", ""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).isEqualTo("STEP 1: partial progress");
        assertThat(h.calls()).isEqualTo(3);
        assertThat(engine.iterationBudget(h.process)).isEqualTo(3);
    }

    @Test
    void withoutACap_theBudgetIsZero() {
        assertThat(engine.iterationBudget(h.process)).isZero();
    }

    @Test
    void theInterrupt_stopsBeforeThePlanningCall() {
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);
        h.script(text("1. never"));

        assertThatThrownBy(this::run).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
    }

    @Test
    void stepAndRevisionParsing() {
        assertThat(NutrimatEspresso.stepOf("STEP 3: reading")).isEqualTo(3);
        assertThat(NutrimatEspresso.stepOf("**STEP 12:** x")).isEqualTo(12);
        assertThat(NutrimatEspresso.stepOf("no step here")).isNull();
        assertThat(NutrimatEspresso.revisedPlanOf("STEP 2: x\nPLAN REVISED: 1. a"))
                .isEqualTo("1. a");
        assertThat(NutrimatEspresso.revisedPlanOf("STEP 2: x")).isNull();
    }
}
