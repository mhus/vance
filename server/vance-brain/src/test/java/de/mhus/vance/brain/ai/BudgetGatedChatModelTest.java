package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.BudgetWindow;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link BudgetGatedChatModel} and its streaming twin: a refused attempt never
 * reaches the wrapped model — and therefore never reaches the accounting
 * decorator below it, which would book it.
 */
class BudgetGatedChatModelTest {

    private static final ChatRequest REQUEST =
            ChatRequest.builder().messages(UserMessage.from("hi")).build();
    private static final CallAttribution ATTRIBUTION = CallAttribution.ofService("acme", "p1", "arthur");

    private static final UsageGate REFUSING = (attribution, model) -> {
        throw new LlmBudgetExceededException(new LlmBudgetService.WindowStatus(
                BudgetScope.PROJECT,
                "p1",
                BudgetWindow.DAILY,
                LocalDate.of(2026, 10, 16),
                1,
                2,
                "USD",
                80,
                0,
                Map.of()));
    };

    @Test
    void sync_refused_neverCallsTheDelegate() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel gated = new BudgetGatedChatModel(counting(calls), ATTRIBUTION, model(), REFUSING);

        assertThatThrownBy(() -> gated.chat(REQUEST)).isInstanceOf(LlmBudgetExceededException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void sync_admitted_passesThrough() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel gated = new BudgetGatedChatModel(counting(calls), ATTRIBUTION, model(), UsageGate.OPEN);

        assertThat(gated.chat(REQUEST).aiMessage().text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void streaming_refused_reportsThroughOnError_andNeverCallsTheDelegate() {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
            }
        };
        StreamingChatModel gated = new BudgetGatedStreamingChatModel(delegate, ATTRIBUTION, model(), REFUSING);

        AtomicReference<Throwable> error = new AtomicReference<>();
        gated.chat(REQUEST, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse complete) {}

            @Override
            public void onError(Throwable t) {
                error.set(t);
            }
        });

        assertThat(error.get()).isInstanceOf(LlmBudgetExceededException.class);
        assertThat(calls.get()).isZero();
    }

    private static ChatModel counting(AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
    }

    private static ModelInfo model() {
        return new ModelInfo(
                "openai",
                "kimi-k3",
                200_000,
                8192,
                ModelSize.LARGE,
                Set.<ModelCapability>of(),
                60,
                2,
                false,
                null,
                new ModelInfo.Pricing("USD", 3.0, 15.0, null, null),
                OutputTokenParam.MAX_TOKENS,
                Set.<SamplingParam>of(),
                null,
                null,
                false);
    }
}
