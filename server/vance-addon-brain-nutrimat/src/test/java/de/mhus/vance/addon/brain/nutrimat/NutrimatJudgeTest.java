package de.mhus.vance.addon.brain.nutrimat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Judge decision parsing and the never-block-the-turn degradation policy.
 * The LLM side is mocked — the judge is pure glue between the loop and
 * {@link LightLlmService}.
 */
class NutrimatJudgeTest {

    private final LightLlmService lightLlm = mock(LightLlmService.class);
    private final NutrimatJudge judge = new NutrimatJudge(lightLlm);

    private static ThinkProcessDocument process() {
        return new ThinkProcessDocument();
    }

    @Test
    void judgeExhausted_extendCarriesTheNudge() {
        when(lightLlm.callForJson(any()))
                .thenReturn(Map.of("decision", "extend", "reason", "about to act", "nudge", "read the pom"));

        NutrimatJudge.ExhaustedJudgment v = judge.judgeExhausted(process(), "goal", "partial", 10);

        assertThat(v.extend()).isTrue();
        assertThat(v.text()).isEqualTo("read the pom");
        assertThat(v.reason()).isEqualTo("about to act");
    }

    @Test
    void judgeExhausted_synthesizeCarriesTheAnswer() {
        when(lightLlm.callForJson(any()))
                .thenReturn(Map.of("decision", "synthesize", "reason", "circling", "answer", "the answer"));

        NutrimatJudge.ExhaustedJudgment v = judge.judgeExhausted(process(), "goal", "partial", 10);

        assertThat(v.extend()).isFalse();
        assertThat(v.text()).isEqualTo("the answer");
    }

    @Test
    void judgeExhausted_unknownDecisionCollapsesToSynthesize() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("decision", "banana"));

        NutrimatJudge.ExhaustedJudgment v = judge.judgeExhausted(process(), "goal", "the gathered work", 10);

        assertThat(v.extend()).isFalse();
        assertThat(v.text()).isEqualTo("the gathered work");
    }

    @Test
    void judgeExhausted_llmFailureDegradesToSynthesizeWithGatheredText() {
        // A judge that cannot deliver must never block the turn.
        when(lightLlm.callForJson(any())).thenThrow(new LightLlmException("provider down"));

        NutrimatJudge.ExhaustedJudgment v = judge.judgeExhausted(process(), "goal", "the gathered work", 10);

        assertThat(v.extend()).isFalse();
        assertThat(v.text()).isEqualTo("the gathered work");
        assertThat(v.reason()).isEqualTo("judge-llm-failed");
    }

    @Test
    void judgeContinue_continueCarriesTheNudge() {
        when(lightLlm.callForJson(any()))
                .thenReturn(
                        Map.of("decision", "continue", "reason", "promised work missing", "nudge", "read the file"));

        NutrimatJudge.ContinueJudgment v = judge.judgeContinue(process(), "goal", "draft", 3);

        assertThat(v.done()).isFalse();
        assertThat(v.nudge()).isEqualTo("read the file");
    }

    @Test
    void judgeContinue_doneIsTheDefaultForUnknownDecisions() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("decision", "banana"));

        assertThat(judge.judgeContinue(process(), "goal", "draft", 3).done()).isTrue();
    }

    @Test
    void judgeContinue_llmFailureDegradesToDone() {
        // Refusing to stop would re-create the deadlock the judge prevents.
        when(lightLlm.callForJson(any())).thenThrow(new LightLlmException("provider down"));

        NutrimatJudge.ContinueJudgment v = judge.judgeContinue(process(), "goal", "draft", 3);

        assertThat(v.done()).isTrue();
        assertThat(v.reason()).isEqualTo("judge-llm-failed");
    }

    @Test
    void reportRound_carriesTheAccount() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("report", "I read the pom and listed the modules"));

        NutrimatJudge.RoundReport v = judge.reportRound(process(), "goal", "draft", 4);

        assertThat(v.report()).isEqualTo("I read the pom and listed the modules");
    }

    @Test
    void reportRound_emptyReportDegradesToTheDraftText() {
        // The account is never blank by contract — the obligation survives the judge.
        when(lightLlm.callForJson(any())).thenReturn(Map.of("report", "   "));

        NutrimatJudge.RoundReport v = judge.reportRound(process(), "goal", "the draft text", 4);

        assertThat(v.report()).isEqualTo("the draft text");
    }

    @Test
    void reportRound_llmFailureDegradesToTheDraftText() {
        when(lightLlm.callForJson(any())).thenThrow(new LightLlmException("provider down"));

        NutrimatJudge.RoundReport v = judge.reportRound(process(), "goal", "the draft text", 4);

        assertThat(v.report()).isEqualTo("the draft text");
    }

    @Test
    void reportRound_blankEverythingStillYieldsAFallbackSentence() {
        when(lightLlm.callForJson(any())).thenThrow(new LightLlmException("provider down"));

        NutrimatJudge.RoundReport v = judge.reportRound(process(), "goal", "", 4);

        assertThat(v.report()).isNotBlank();
    }
}
