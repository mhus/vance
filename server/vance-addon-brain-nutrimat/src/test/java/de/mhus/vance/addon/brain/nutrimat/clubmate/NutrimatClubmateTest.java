package de.mhus.vance.addon.brain.nutrimat.clubmate;

import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.text;
import static de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.LoopStats;
import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat.TurnOutcome;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import de.mhus.vance.addon.brain.nutrimat.NutrimatLoopHarness;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * clubmate's own loop: a budget segment, and at exhaustion the judge —
 * extend with a fresh budget, or synthesize the answer. The judge sees the
 * work, not just the text.
 */
class NutrimatClubmateTest {

    private final NutrimatLoopHarness h = new NutrimatLoopHarness();
    private final NutrimatJudge judge = mock(NutrimatJudge.class);

    // Positional args on purpose — a constructor change must break compile.
    private final NutrimatClubmate engine = new NutrimatClubmate(
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
            h.notifications,
            judge);

    private TurnOutcome run(LoopStats stats) {
        return engine.runLoop(h.process, h.ctx, h.inputs(), stats);
    }

    @Test
    void answerWithinTheBudget_isAnOrdinaryReply_noJudge() {
        h.script(toolCall("{\"n\":1}", ""), text("the answer"));

        TurnOutcome out = run(new LoopStats());

        assertThat(out.finalText()).isEqualTo("the answer");
        assertThat(out.awaitingUserInput()).isFalse();
    }

    @Test
    void exhausted_judgeExtend_grantsAFreshBudget() {
        h.process.getEngineParams().put("maxIterations", 2);
        h.script(toolCall("{\"n\":1}", ""), toolCall("{\"n\":2}", ""), toolCall("{\"n\":3}", ""), text("finally"));
        when(judge.judgeExhausted(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ExhaustedJudgment(true, "keep going", "progress visible"));
        LoopStats stats = new LoopStats();

        TurnOutcome out = run(stats);

        assertThat(out.finalText()).isEqualTo("finally");
        assertThat(stats.extensions).isEqualTo(1);
        assertThat(h.contents()).anyMatch(c -> c.contains("(extended 1×)"));
    }

    @Test
    void exhausted_judgeSynthesize_endsTheTurnAsAnAnswer() {
        h.process.getEngineParams().put("maxIterations", 1);
        h.script(toolCall("{\"n\":1}", "partial"));
        when(judge.judgeExhausted(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ExhaustedJudgment(false, "the synthesized answer", "enough"));

        TurnOutcome out = run(new LoopStats());

        assertThat(out.finalText()).isEqualTo("the synthesized answer");
        assertThat(out.recovered())
                .as("the judge vouched — a normal end, not a failure")
                .isFalse();
    }

    @Test
    void judge_seesTheToolWork_notJustTheText() {
        h.process.getEngineParams().put("maxIterations", 1);
        h.script(toolCall("{\"path\":\"pom.xml\"}", "reading"));
        when(judge.judgeExhausted(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new NutrimatJudge.ExhaustedJudgment(false, "answer", "enough"));

        run(new LoopStats());

        verify(judge)
                .judgeExhausted(
                        any(),
                        eq("do the thing"),
                        argThat(g -> g.contains("reading") && g.contains("doc_read {\"path\":\"pom.xml\"}")),
                        eq(1));
    }

    @Test
    void emptyReply_isAFailure_neverASilentEnd() {
        h.script(text(""));

        TurnOutcome out = run(new LoopStats());

        assertThat(out.recovered()).isTrue();
        assertThat(out.finalText()).contains("empty response");
    }

    @Test
    void iterationBudget_isTheNatureOwnedJudgeBudget() {
        ThinkProcessDocument withRecipe = new ThinkProcessDocument();
        withRecipe.setEngineParams(Map.of("maxIterations", 30));

        assertThat(engine.iterationBudget(withRecipe)).isEqualTo(30);
        assertThat(engine.iterationBudget(new ThinkProcessDocument())).isEqualTo(12);
    }
}
