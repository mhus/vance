package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.brain.ai.decision.AiDecisionConfig;
import de.mhus.vance.brain.ai.decision.DecisionModelInfo;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemPreparedCall;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptZemApiTest {

    private static final ToolInvocationContext SCOPE = new ToolInvocationContext("acme", "p1", "s1", "proc-1", "mara");

    private final ZemService service = mock(ZemService.class);
    private ZemPreparedCall prepared;

    @BeforeEach
    void setUp() {
        prepared = new ZemPreparedCall(
                "acme",
                "p1",
                "proc-1",
                "mara",
                "command-safety",
                "openrouter:typesafe/jev-1.13",
                new AiDecisionConfig("openai", "openrouter", "typesafe/jev-1.13", "k", null, 10),
                new DecisionModelInfo(
                        "openrouter", "typesafe/jev-1.13", 10, 64, 255, 10, true, null, 0.042, null, null),
                List.of(),
                List.of(),
                3,
                null,
                null);
        when(service.prepare(any(ZemDecideRequest.class))).thenReturn(prepared);
    }

    private static ZemDecisionDto unsafe(double p) {
        return ZemDecisionDto.builder()
                .model("jev")
                .answers(List.of(ZemAnswerDto.builder()
                        .name("unsafe")
                        .type(ZemAnswerType.NOUL)
                        .noul(p)
                        .build()))
                .build();
    }

    @Test
    void decide_returns_answers_by_question_name() {
        when(service.decide(eq(prepared), eq(Map.of("command", "rm -rf /")), isNull()))
                .thenReturn(unsafe(0.97));
        VanceScriptApi.ScriptZemApi api = new VanceScriptApi.ScriptZemApi(service, SCOPE);

        Map<String, Object> d = api.decide("command-safety", Map.of("command", "rm -rf /"));

        assertThat(((Map<?, ?>) d.get("unsafe")).get("noul")).isEqualTo(0.97);
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).prepare(request.capture());
        assertThat(request.getValue().getProfile()).isEqualTo("command-safety");
        assertThat(request.getValue().getTenantId()).isEqualTo("acme");
    }

    @Test
    void inline_questions_are_parsed() {
        when(service.decide(eq(prepared), eq("text"), isNull()))
                .thenReturn(
                        ZemDecisionDto.builder().model("m").answers(List.of()).build());
        VanceScriptApi.ScriptZemApi api = new VanceScriptApi.ScriptZemApi(service, SCOPE);

        api.decide(null, "text", List.of(Map.of("name", "spam", "type", "noul", "instructions", "Spam?")));

        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).prepare(request.capture());
        assertThat(request.getValue().getQuestions())
                .singleElement()
                .satisfies(q -> assertThat(q.getName()).isEqualTo("spam"));
    }

    @Test
    void failure_throws_with_the_reason_in_brackets() {
        when(service.decide(eq(prepared), any(), isNull()))
                .thenThrow(new ZemException(ZemException.Reason.RATE_LIMITED, "slow down"));
        VanceScriptApi.ScriptZemApi api = new VanceScriptApi.ScriptZemApi(service, SCOPE);

        assertThatThrownBy(() -> api.decide("p", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessage("vance.zem.decide(p) failed [rate_limited]: slow down");
    }

    @Test
    void call_policy_sees_the_resolved_model_and_can_refuse_before_the_call() {
        ScriptCallPolicy policy = mock(ScriptCallPolicy.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("model not approved"))
                .when(policy)
                .beforeDecision("openrouter:typesafe/jev-1.13");
        VanceScriptApi.ScriptZemApi api = new VanceScriptApi.ScriptZemApi(service, SCOPE, policy);

        assertThatThrownBy(() -> api.decide("command-safety", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("refused: model not approved");
        verify(service, never()).decide(any(ZemPreparedCall.class), any(), any());
    }
}
