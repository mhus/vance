package de.mhus.vance.addon.brain.nutrimat.cappuccino;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopInputs;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatExhaustedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * cappuccino's own loop: redbull's hard budget, but every model call sees
 * a transient budget note (removed right after the call); the last round
 * is told to answer now; exhaustion raises the exhausted error; no
 * continue-gate.
 */
class NutrimatCappuccinoTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatCappuccino engine = new NutrimatCappuccino(
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

    /** Snapshot of the messages each model call saw. */
    private final List<List<ChatMessage>> requests = new ArrayList<>();

    private final LoopInputs in = h.inputs();

    @BeforeEach
    void captureRequests() {
        when(h.turnContextHandlers.apply(any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> messages = inv.getArgument(0);
            requests.add(new ArrayList<>(messages));
            return messages;
        });
    }

    private TurnOutcome run() {
        return engine.runLoop(h.process, h.ctx, in, new LoopStats());
    }

    private static String lastText(List<ChatMessage> request) {
        return ((SystemMessage) request.get(request.size() - 1)).text();
    }

    @Test
    void budgetNote_isVisibleInTheRequest_andGoneAfterwards() {
        h.process.getEngineParams().put("maxIterations", 5);
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));

        run();

        assertThat(lastText(requests.get(0))).startsWith("BUDGET: round 1 of 5 (4 left), 0 min elapsed.");
        assertThat(lastText(requests.get(1))).startsWith("BUDGET: round 2 of 5 (3 left)");
        assertThat(in.messages())
                .as("the budget note is transient — it never piles up")
                .noneMatch(m -> m instanceof SystemMessage s && s.text().startsWith("BUDGET:"));
    }

    @Test
    void lastRound_isToldToAnswerNow() {
        h.process.getEngineParams().put("maxIterations", 2);
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));

        run();

        assertThat(lastText(requests.get(0))).doesNotContain("LAST ROUND");
        assertThat(lastText(requests.get(1))).contains("LAST ROUND: answer now without calling tools");
    }

    @Test
    void answerWithinTheBudget_isAnOrdinaryReply() {
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));

        TurnOutcome out = run();

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput())
                .as("an answer leaves the process IDLE")
                .isFalse();
        assertThat(out.recovered()).isFalse();
    }

    @Test
    void reachingTheBudget_raisesTheExhaustedError() {
        h.process.getEngineParams().put("maxIterations", 3);
        h.script(toolCall("{\"n\":1}", "partial progress"));

        assertThatThrownBy(this::run)
                .isInstanceOf(NutrimatExhaustedException.class)
                .hasMessageContaining("exhausted")
                .hasMessageContaining("3");
        assertThat(h.calls()).isEqualTo(3);
    }

    @Test
    void llmFailure_removesTheNote_andFails() {
        h.script(toolCall("{\"n\":1}", ""), text("never"));
        h.failAt(2, new IllegalStateException("stream gone"));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("stream gone");
        assertThat(in.messages())
                .noneMatch(m -> m instanceof SystemMessage s && s.text().startsWith("BUDGET:"));
    }

    @Test
    void emptyReply_isAHardStop_neverASilentEnd() {
        h.script(text(""));

        TurnOutcome out = run();

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void interrupt_comesFromRound_andIsNeverSwallowed() {
        h.script(text("never reached"));
        when(h.thinkProcessService.isHaltRequested("p1")).thenReturn(true);

        assertThatThrownBy(this::run).isInstanceOf(NutrimatInterruptedException.class);
        assertThat(h.calls()).isZero();
        assertThat(in.messages())
                .noneMatch(m -> m instanceof SystemMessage s && s.text().startsWith("BUDGET:"));
    }

    @Test
    void iterationBudget_defaultsTo20() {
        ThinkProcessDocument withRecipe = new ThinkProcessDocument();
        withRecipe.setEngineParams(Map.of("maxIterations", 12));

        assertThat(engine.iterationBudget(withRecipe)).isEqualTo(12);
        assertThat(engine.iterationBudget(new ThinkProcessDocument())).isEqualTo(20);
    }
}
