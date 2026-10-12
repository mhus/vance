package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptZemApiTest {

    private final ZemService service = mock(ZemService.class);
    private final VanceScriptApi.ScriptZemApi api =
            new VanceScriptApi.ScriptZemApi(service, new ToolInvocationContext("acme", "p1", "s1", "proc-1", "mara"));

    @Test
    void decide_returns_answers_by_question_name() {
        when(service.decide(any(ZemDecideRequest.class), eq(Map.of("command", "rm -rf /"))))
                .thenReturn(ZemDecisionDto.builder()
                        .model("jev")
                        .answers(List.of(ZemAnswerDto.builder()
                                .name("unsafe")
                                .type(ZemAnswerType.NOUL)
                                .noul(0.97)
                                .build()))
                        .build());

        Map<String, Object> d = api.decide("command-safety", Map.of("command", "rm -rf /"));

        assertThat(((Map<?, ?>) d.get("unsafe")).get("noul")).isEqualTo(0.97);
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).decide(request.capture(), any());
        assertThat(request.getValue().getProfile()).isEqualTo("command-safety");
        assertThat(request.getValue().getTenantId()).isEqualTo("acme");
    }

    @Test
    void inline_questions_are_parsed() {
        when(service.decide(any(ZemDecideRequest.class), any()))
                .thenReturn(
                        ZemDecisionDto.builder().model("m").answers(List.of()).build());

        api.decide(null, "text", List.of(Map.of("name", "spam", "type", "noul", "instructions", "Spam?")));

        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).decide(request.capture(), eq("text"));
        assertThat(request.getValue().getQuestions())
                .singleElement()
                .satisfies(q -> assertThat(q.getName()).isEqualTo("spam"));
    }

    @Test
    void failure_throws_with_the_reason_in_brackets() {
        when(service.decide(any(ZemDecideRequest.class), any()))
                .thenThrow(new ZemException(ZemException.Reason.RATE_LIMITED, "slow down"));

        assertThatThrownBy(() -> api.decide("p", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessage("vance.zem.decide(p) failed [rate_limited]: slow down");
    }
}
