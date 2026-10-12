package de.mhus.vance.brain.tools.zem;

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
import de.mhus.vance.api.zem.ZemQuestionDto;
import de.mhus.vance.api.zem.ZemQuestionType;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ZemDecideToolTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext("acme", "p1", "s1", "proc-1", "mara");

    private final ZemService service = mock(ZemService.class);
    private final ZemDecideTool tool = new ZemDecideTool(service);

    private static ZemDecisionDto decision() {
        return ZemDecisionDto.builder()
                .model("nimble")
                .answers(List.of(ZemAnswerDto.builder()
                        .name("intent")
                        .type(ZemAnswerType.CHOICE)
                        .choice("refund")
                        .confidence(0.91234)
                        .probabilities(Map.of("refund", 0.95, "other", 0.05))
                        .build()))
                .latencyMs(120)
                .build();
    }

    @Test
    void flat_question_fields_become_system_one_criteria() {
        List<ZemQuestionDto> questions = ZemDecideTool.questions(List.of(
                Map.of("name", "intent", "type", "choice", "instructions", "?", "options", Map.of("a", "A", "b", "B")),
                Map.of("name", "anger", "type", "score", "instructions", "?", "levels", List.of("calm", "angry")),
                Map.of("name", "urgent", "type", "noul", "instructions", "?", "yes", "today", "no", "later"),
                Map.of("name", "spam", "type", "noul", "instructions", "?")));

        assertThat(questions)
                .extracting(ZemQuestionDto::getType)
                .containsExactly(
                        ZemQuestionType.CHOICE, ZemQuestionType.SCORE, ZemQuestionType.NOUL, ZemQuestionType.NOUL);
        assertThat(questions.get(0).getCriteria()).isEqualTo(Map.of("a", "A", "b", "B"));
        assertThat(questions.get(1).getCriteria()).isEqualTo(List.of("calm", "angry"));
        assertThat(questions.get(2).getCriteria()).isEqualTo(Map.of("true", "today", "false", "later"));
        assertThat(questions.get(3).getCriteria()).isNull();
    }

    @Test
    void success_returns_answers_keyed_by_name() {
        when(service.decide(any(ZemDecideRequest.class), any())).thenReturn(decision());

        Map<String, Object> out = tool.invoke(
                Map.of(
                        "state", Map.of("message", "money back please"),
                        "questions",
                                List.of(Map.of(
                                        "name", "intent",
                                        "type", "choice",
                                        "instructions", "Intent of `message`?",
                                        "options", Map.of("refund", "money back", "other", "else")))),
                CTX);

        assertThat(out).containsEntry("model", "nimble").containsEntry("latencyMs", 120L);
        Map<?, ?> intent = (Map<?, ?>) ((Map<?, ?>) out.get("answers")).get("intent");
        assertThat(intent.get("choice")).isEqualTo("refund");
        assertThat(intent.get("confidence")).isEqualTo(0.912);
        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).decide(request.capture(), eq(Map.of("message", "money back please")));
        assertThat(request.getValue().getProcessId()).isEqualTo("proc-1");
        assertThat(request.getValue().getUserId()).isEqualTo("mara");
    }

    @Test
    void text_state_and_profile_only_are_accepted() {
        when(service.decide(any(ZemDecideRequest.class), eq("plain text"))).thenReturn(decision());

        tool.invoke(Map.of("text", "plain text", "profile", "command-safety"), CTX);

        ArgumentCaptor<ZemDecideRequest> request = ArgumentCaptor.forClass(ZemDecideRequest.class);
        verify(service).decide(request.capture(), eq("plain text"));
        assertThat(request.getValue().getProfile()).isEqualTo("command-safety");
    }

    @Test
    void service_failure_is_an_error_object_not_an_exception() {
        when(service.decide(any(ZemDecideRequest.class), any()))
                .thenThrow(new ZemException(ZemException.Reason.INVALID_CHOICE, "no decision model"));

        Map<String, Object> out = tool.invoke(Map.of("text", "x", "profile", "p"), CTX);

        assertThat(out).containsEntry("error", "invalid_choice").containsEntry("retryable", false);
    }

    @Test
    void caller_mistakes_are_tool_exceptions() {
        assertThatThrownBy(() -> tool.invoke(Map.of("profile", "p"), CTX)).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> tool.invoke(Map.of("text", "x"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'questions' or a 'profile'");
        assertThatThrownBy(() -> tool.invoke(Map.of("text", "x", "state", Map.of("a", 1), "profile", "p"), CTX))
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> tool.invoke(
                        Map.of(
                                "text",
                                "x",
                                "questions",
                                List.of(Map.of("name", "q", "type", "maybe", "instructions", "?"))),
                        CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void is_deferred_and_read_only() {
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.primary()).isFalse();
        assertThat(tool.labels()).contains("read-only");
        assertThat(tool.searchHint()).isNotBlank();
    }
}
