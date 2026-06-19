package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Decision-shape tests for {@link ActionLoopJudgeService}. The LLM
 * call is mocked; what we verify is how the service interprets the
 * reply: extend vs. synthesize, the {@code extensionsLeft==0} override,
 * and the failure-fallback behaviour that keeps the engine from
 * deadlocking when the judge itself is broken.
 */
class ActionLoopJudgeServiceTest {

    private LightLlmService lightLlm;
    private ActionLoopJudgeService judge;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        judge = new ActionLoopJudgeService(lightLlm);
    }

    @Test
    void synthesize_decision_returnsAnswer() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "decision", "synthesize",
                "answer", "Hier ist meine Synthese.",
                "reason", "Looped on the same fetch twice"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(/*extLeft*/ 1, "gathered"));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isEqualTo("Hier ist meine Synthese.");
        assertThat(j.reason()).contains("Looped");
    }

    @Test
    void extend_decision_isExtendWhenBudgetLeft() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "decision", "extend",
                "reason", "Each fetch surfaced new material"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(/*extLeft*/ 1, "gathered"));

        assertThat(j.extend()).isTrue();
        assertThat(j.synthesizedAnswer()).isNull();
    }

    @Test
    void extend_forbidden_whenExtensionsExhausted() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "decision", "extend",
                "answer", "fallback answer",
                "reason", "still progressing"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(/*extLeft*/ 0, "gathered"));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isEqualTo("fallback answer");
    }

    @Test
    void synthesize_emptyAnswer_fallsBackToGatheredText() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "decision", "synthesize",
                "reason", "looped"));

        ActionLoopJudgeService.Judgment j = judge.judge(
                req(1, "Best free text the model emitted so far."));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isEqualTo("Best free text the model emitted so far.");
    }

    @Test
    void llmFailure_returnsSynthesizeWithGatheredText() {
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("provider exhausted"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(1, "what we gathered"));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isEqualTo("what we gathered");
        assertThat(j.reason()).isEqualTo("judge-llm-failed");
    }

    @Test
    void llmFailure_emptyGathered_returnsHardLastResortText() {
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("provider exhausted"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(1, ""));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isNotBlank();
        assertThat(j.synthesizedAnswer()).contains("frag mich");
    }

    @Test
    void unknownDecision_falsToSynthesize() {
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "decision", "panic",
                "answer", "best effort",
                "reason", "model confused"));

        ActionLoopJudgeService.Judgment j = judge.judge(req(1, "gathered"));

        assertThat(j.extend()).isFalse();
        assertThat(j.synthesizedAnswer()).isEqualTo("best effort");
    }

    private static ActionLoopJudgeService.JudgeRequest req(int extLeft, String gathered) {
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("proc-1")
                .tenantId("mhus")
                .projectId("vibecoding")
                .sessionId("sess_xyz")
                .build();
        return new ActionLoopJudgeService.JudgeRequest(
                process,
                "Wie macht pi das?",
                gathered,
                List.of("research_search(query=pi)", "web_fetch(url=...)"),
                12,
                extLeft);
    }
}
