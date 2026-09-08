package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The wrapper stack every engine request goes through
 * ({@link StandardAiChat}: logging → resilience) must not lose action
 * tool calls from a completed streaming response — structured-action
 * engines (Arthur's {@code arthur_action}) receive their actions as
 * tool-execution requests on the final {@link AiMessage}, and an E2E
 * test drives exactly that shape through the scripted provider.
 */
class StandardAiChatToolCallPassThroughTest {

    @Test
    void wrapperStack_keepsToolExecutionRequestsOnComplete() {
        AiMessage actionMessage = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("arthur_action")
                        .arguments("{\"type\":\"WAIT\",\"reason\":\"r\",\"message\":\"m\"}")
                        .build()))
                .build();
        StreamingChatModel raw = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(actionMessage)
                        .finishReason(FinishReason.STOP)
                        .build());
            }
        };

        StandardAiChat chat = new StandardAiChat(
                "scripted:chat",
                ProviderType.SCRIPTED,
                mock(ChatModel.class),
                raw,
                AiChatOptions.builder()
                        .metricService(
                                new MetricService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                        .build());

        AtomicReference<ChatResponse> delivered = new AtomicReference<>();
        List<ChatMessage> messages = List.of(UserMessage.from("hi"));
        chat.streamingChatModel()
                .chat(ChatRequest.builder().messages(messages).build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse complete) {
                        delivered.set(complete);
                    }

                    @Override
                    public void onError(Throwable error) {
                        throw new RuntimeException(error);
                    }
                });

        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().aiMessage().hasToolExecutionRequests())
                .as("the wrapper stack (logging → resilience) must keep the tool call")
                .isTrue();
        assertThat(delivered.get().aiMessage().toolExecutionRequests().get(0).name())
                .isEqualTo("arthur_action");
    }
}
