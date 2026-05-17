package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.DistributionSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmCallStatsLoggerTest {

    @Test
    void countRequestChars_sumsSystemUserAndToolResultMessages() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("sys-12"),    // 6
                        UserMessage.from("hello"),       // 5
                        ToolExecutionResultMessage.from("call-1", "tool", "result-x")))  // 8
                .build();

        assertThat(LlmCallStatsLogger.countRequestChars(request)).isEqualTo(6 + 5 + 8);
    }

    @Test
    void countRequestChars_includesAiMessageWithToolCalls() {
        AiMessage ai = AiMessage.from(
                "ok",  // 2
                List.of(ToolExecutionRequest.builder()
                        .id("c1")
                        .name("read_file")  // 9
                        .arguments("{\"p\":\"x\"}")  // 9
                        .build()));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ai))
                .build();

        assertThat(LlmCallStatsLogger.countRequestChars(request)).isEqualTo(2 + 9 + 9);
    }

    @Test
    void countResponseChars_textPlusToolCallArgs() {
        AiMessage ai = AiMessage.from(
                "answer",  // 6
                List.of(ToolExecutionRequest.builder()
                        .id("c1")
                        .name("tool")  // 4
                        .arguments("{}")  // 2
                        .build()));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(ai)
                .build();

        assertThat(LlmCallStatsLogger.countResponseChars(response)).isEqualTo(6 + 4 + 2);
    }

    @Test
    void countResponseChars_nullResponseIsZero() {
        assertThat(LlmCallStatsLogger.countResponseChars(null)).isZero();
    }

    @Test
    void countRequestChars_nullRequestIsZero() {
        assertThat(LlmCallStatsLogger.countRequestChars(null)).isZero();
    }

    @Test
    void record_pushesCharMetricsWithModelTag() {
        MetricService metrics = mock(MetricService.class);
        DistributionSummary inSummary = mock(DistributionSummary.class);
        DistributionSummary outSummary = mock(DistributionSummary.class);
        when(metrics.summary(eq("vance.llm.chars.input"), eq("model"), eq("anthropic:sonnet")))
                .thenReturn(inSummary);
        when(metrics.summary(eq("vance.llm.chars.output"), eq("model"), eq("anthropic:sonnet")))
                .thenReturn(outSummary);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello world")))  // 11
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("hi"))  // 2
                .tokenUsage(new TokenUsage(20, 5))
                .finishReason(FinishReason.STOP)
                .build();

        LlmCallStatsLogger.record("anthropic:sonnet", request, response, 123L, metrics);

        verify(inSummary).record(11.0);
        verify(outSummary).record(2.0);
    }

    @Test
    void record_skipsMetricsWhenServiceIsNull() {
        // Just verify it doesn't blow up — no MetricService interaction.
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("x")))
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("y"))
                .build();

        LlmCallStatsLogger.record("m", request, response, 1L, null);
    }

    @Test
    void record_handlesNullResponse_emitsZeroOutChars() {
        MetricService metrics = mock(MetricService.class);
        DistributionSummary inSummary = mock(DistributionSummary.class);
        DistributionSummary outSummary = mock(DistributionSummary.class);
        when(metrics.summary(anyString(), anyString(), anyString()))
                .thenReturn(inSummary, outSummary);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("abc")))  // 3
                .build();

        LlmCallStatsLogger.record("p:m", request, null, 99L, metrics);

        // First call goes to chars.input (3), second to chars.output (0).
        verify(inSummary).record(3.0);
        verify(outSummary).record(0.0);
    }

    @Test
    void record_unknownModelTag_whenNameBlank() {
        MetricService metrics = mock(MetricService.class);
        DistributionSummary anySummary = mock(DistributionSummary.class);
        when(metrics.summary(anyString(), eq("model"), eq("unknown")))
                .thenReturn(anySummary);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of((ChatMessage) UserMessage.from("x")))
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("y"))
                .build();

        LlmCallStatsLogger.record("", request, response, 1L, metrics);

        verify(metrics).summary("vance.llm.chars.input", "model", "unknown");
        verify(metrics).summary("vance.llm.chars.output", "model", "unknown");
        verify(metrics, never()).summary(anyString(), eq("model"), eq(""));
    }
}
