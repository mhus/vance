package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.anthropic.AnthropicTokenUsage;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.UsageKind;
import de.mhus.vance.shared.llmusage.UsageOutcome;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The contract that moved down from the engines: every attempt on the wire
 * is booked, with the caller that pays and the numbers the provider
 * reported.
 *
 * <p>Four properties are pinned here because each of them was broken before
 * the decorator existed — a failed attempt vanished, cache tokens were
 * hardcoded to zero, retries collapsed into one row, and four call sites
 * never booked at all.
 */
class UsageAccountingChatModelTest {

    private static final CallAttribution ATTRIBUTION = new CallAttribution(
            "acme", "demo", "sess-1", "proc-1", "jeltz", "extract");

    @Test
    void booksASuccessfulCallWithTheCallerThatPays() {
        RecordingSink sink = new RecordingSink();
        ChatModel model = accounting(
                new FakeChatModel(request -> response(new TokenUsage(1200, 340))),
                pricedModel(), sink);

        model.chat(request());

        assertThat(sink.calls).hasSize(1);
        assertThat(sink.calls.get(0).attribution).isEqualTo(ATTRIBUTION);
        UsageMeasurement m = sink.calls.get(0).measurement;
        assertThat(m.outcome()).isEqualTo(UsageOutcome.SUCCESS);
        assertThat(m.kind()).isEqualTo(UsageKind.CHAT);
        assertThat(m.tokensIn()).isEqualTo(1200);
        assertThat(m.tokensOut()).isEqualTo(340);
        assertThat(m.attempt()).isEqualTo(1);
        assertThat(m.modelAlias()).isEqualTo("cortecs:kimi-k3");
        assertThat(m.providerType()).isEqualTo("openai");
    }

    @Test
    void readsCacheTokensInsteadOfBookingThemAsZero() {
        // The whole point: with prompt caching most of the input arrives as
        // cache reads, and both former writers hardcoded these to 0 — so a
        // cached workload was billed for a fraction of what it sent.
        RecordingSink sink = new RecordingSink();
        ChatModel model = accounting(
                new FakeChatModel(request -> response(new AnthropicTokenUsage(
                        300, 120, /*cacheCreation*/ 5_000, /*cacheRead*/ 40_000))),
                pricedModel(), sink);

        model.chat(request());

        UsageMeasurement m = sink.calls.get(0).measurement;
        assertThat(m.tokensIn()).isEqualTo(300);
        assertThat(m.cacheReadTokens()).isEqualTo(40_000);
        assertThat(m.cacheWriteTokens()).isEqualTo(5_000);
    }

    @Test
    void booksAFailedAttemptEvenWithoutTokenCounts() {
        // The provider consumed the prompt and then raised. Before, the
        // engines recorded after the call returned, so this cost nothing on
        // paper.
        RecordingSink sink = new RecordingSink();
        ChatModel model = accounting(new FakeChatModel(request -> {
            throw new IllegalStateException("429 rate limited");
        }), pricedModel(), sink);

        assertThatThrownBy(() -> model.chat(request()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sink.calls).hasSize(1);
        UsageMeasurement m = sink.calls.get(0).measurement;
        assertThat(m.outcome()).isEqualTo(UsageOutcome.FAILED);
        assertThat(m.isEmpty()).isFalse();
    }

    @Test
    void countsEachAttemptSeparately() {
        // Sitting inside the retry layer means a retry storm produces one row
        // per attempt, each numbered — not a single row for the winner.
        RecordingSink sink = new RecordingSink();
        List<Boolean> fail = new ArrayList<>(List.of(true, true, false));
        ChatModel model = accounting(new FakeChatModel(request -> {
            if (fail.remove(0)) throw new IllegalStateException("503");
            return response(new TokenUsage(10, 5));
        }), pricedModel(), sink);

        assertThatThrownBy(() -> model.chat(request())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> model.chat(request())).isInstanceOf(IllegalStateException.class);
        model.chat(request());

        assertThat(sink.calls).hasSize(3);
        assertThat(sink.calls.stream().map(c -> c.measurement.attempt()).toList())
                .containsExactly(1, 2, 3);
        assertThat(sink.calls.get(2).measurement.outcome()).isEqualTo(UsageOutcome.SUCCESS);
    }

    @Test
    void skipsSuccessfulCallsThatReportedNoUsageAtAll() {
        RecordingSink sink = new RecordingSink();
        ChatModel model = accounting(
                new FakeChatModel(request -> response(null)), pricedModel(), sink);

        model.chat(request());

        assertThat(sink.calls).isEmpty();
    }

    @Test
    void aSinkThatThrowsDoesNotBreakTheTurn() {
        ChatModel model = new UsageAccountingChatModel(
                new FakeChatModel(request -> response(new TokenUsage(10, 5))),
                ATTRIBUTION, pricedModel(), "cortecs",
                (a, m) -> { throw new IllegalStateException("mongo down"); });

        ChatResponse out = model.chat(request());
        assertThat(out.aiMessage().text()).isEqualTo("ok");
    }

    // ──────────────────── helpers ────────────────────

    private static ChatModel accounting(ChatModel delegate, ModelInfo info, UsageSink sink) {
        return new UsageAccountingChatModel(delegate, ATTRIBUTION, info, "cortecs", sink);
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    private static ChatResponse response(@Nullable TokenUsage usage) {
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(AiMessage.from("ok"));
        if (usage != null) b.tokenUsage(usage);
        return b.build();
    }

    private static ModelInfo pricedModel() {
        return new ModelInfo(
                "openai", "kimi-k3",
                200_000, 8192,
                ModelSize.LARGE, Set.<ModelCapability>of(),
                60, 2, false, null,
                new ModelInfo.Pricing("EUR", 2.693, 13.464, 0.3, 3.75),
                OutputTokenParam.MAX_TOKENS, Set.<SamplingParam>of(), null, null, false);
    }

    /** {@link ChatModel} is not a functional interface — hand-rolled stub. */
    private static final class FakeChatModel implements ChatModel {
        private final java.util.function.Function<ChatRequest, ChatResponse> behaviour;

        FakeChatModel(java.util.function.Function<ChatRequest, ChatResponse> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return behaviour.apply(request);
        }
    }

    private record Booked(CallAttribution attribution, UsageMeasurement measurement) {}

    private static final class RecordingSink implements UsageSink {
        private final List<Booked> calls = new ArrayList<>();

        @Override
        public void onCall(CallAttribution attribution, UsageMeasurement measurement) {
            calls.add(new Booked(attribution, measurement));
        }
    }
}
