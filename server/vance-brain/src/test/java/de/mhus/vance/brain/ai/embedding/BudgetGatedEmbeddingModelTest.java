package de.mhus.vance.brain.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.LlmBudgetExceededException;
import de.mhus.vance.brain.ai.LlmBudgetGate;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.OutputTokenParam;
import de.mhus.vance.brain.ai.UsageGate;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.llmusage.BudgetScope;
import de.mhus.vance.shared.llmusage.BudgetWindow;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Embeddings pass the same budget gate as chat calls: a refused call never
 * reaches the provider, and {@link EmbeddingModelService} attaches the gate
 * for every charged model.
 */
class BudgetGatedEmbeddingModelTest {

    private static final CallAttribution RAG = CallAttribution.ofService("acme", "p1", LlmUsageService.CALLER_RAG);

    @Test
    void exhaustedBudget_refusesAPricedEmbeddingCall_withoutTouchingTheProvider() {
        LlmBudgetService budgetService = mock(LlmBudgetService.class);
        when(budgetService.check("acme", "p1")).thenReturn(new LlmBudgetService.Verdict(exhausted(), List.of()));
        UsageGate gate =
                new LlmBudgetGate(budgetService, mock(NotificationService.class), mock(ThinkProcessService.class));
        AtomicInteger wireCalls = new AtomicInteger();
        EmbeddingModel gated = new BudgetGatedEmbeddingModel(counting(wireCalls), RAG, model(pricing(0.02)), gate);

        assertThatThrownBy(() -> gated.embedAll(List.of(TextSegment.from("a"), TextSegment.from("b"))))
                .isInstanceOf(LlmBudgetExceededException.class);
        assertThatThrownBy(() -> gated.embed("query")).isInstanceOf(LlmBudgetExceededException.class);
        assertThat(wireCalls.get()).isZero();
    }

    @Test
    void roomLeft_passesThrough() {
        AtomicInteger wireCalls = new AtomicInteger();
        EmbeddingModel gated =
                new BudgetGatedEmbeddingModel(counting(wireCalls), RAG, model(pricing(0.02)), UsageGate.OPEN);

        assertThat(gated.embedAll(List.of(TextSegment.from("a"))).content()).hasSize(1);
        assertThat(gated.embed("query").content().dimension()).isEqualTo(3);
        assertThat(wireCalls.get()).isEqualTo(2);
    }

    @Test
    void eachCall_asksTheGateOnce() {
        UsageGate gate = mock(UsageGate.class);
        ModelInfo info = model(pricing(0.02));
        EmbeddingModel gated = new BudgetGatedEmbeddingModel(counting(new AtomicInteger()), RAG, info, gate);

        gated.embed("query");

        verify(gate).admit(RAG, info);
    }

    @Test
    void service_gatesEveryChargedModel() {
        UsageGate gate = mock(UsageGate.class);
        doThrow(new LlmBudgetExceededException(exhausted())).when(gate).admit(eq(RAG), any());
        AtomicInteger wireCalls = new AtomicInteger();
        EmbeddingModelService service = new EmbeddingModelService(List.of(provider(wireCalls)), gate);
        EmbeddingConfig config = new EmbeddingConfig("fake", "fake-embed", "");

        EmbeddingModel charged = service.createEmbeddingModel(config, RAG);

        assertThat(charged).isInstanceOf(BudgetGatedEmbeddingModel.class);
        assertThatThrownBy(() -> charged.embed("query")).isInstanceOf(LlmBudgetExceededException.class);
        assertThat(wireCalls.get()).isZero();
    }

    @Test
    void embeddingModelInfo_carriesTheSameRateAsTheLedger_none() {
        // RagService books embeddings unpriced; the gate must judge them by the
        // same snapshot, so it admits them until embeddings carry a rate.
        ModelInfo info =
                EmbeddingModelService.embeddingModelInfo(new EmbeddingConfig("gemini", "gemini-embedding-001", "k"));

        assertThat(info.provider()).isEqualTo("gemini");
        assertThat(info.modelName()).isEqualTo("gemini-embedding-001");
        assertThat(info.pricing()).isNull();
    }

    // ──────────────────── helpers ────────────────────

    private static LlmBudgetService.WindowStatus exhausted() {
        return new LlmBudgetService.WindowStatus(
                BudgetScope.PROJECT,
                "p1",
                BudgetWindow.DAILY,
                LocalDate.of(2026, 10, 16),
                1_000_000,
                1_000_000,
                "USD",
                80,
                0,
                Map.of());
    }

    private static EmbeddingModel counting(AtomicInteger calls) {
        return new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                calls.incrementAndGet();
                return Response.from(textSegments.stream()
                        .map(s -> Embedding.from(new float[] {1f, 0f, 0f}))
                        .toList());
            }

            @Override
            public Response<Embedding> embed(TextSegment textSegment) {
                calls.incrementAndGet();
                return Response.from(Embedding.from(new float[] {1f, 0f, 0f}));
            }
        };
    }

    private static EmbeddingProvider provider(AtomicInteger calls) {
        return new EmbeddingProvider() {
            @Override
            public String getName() {
                return "fake";
            }

            @Override
            public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
                return counting(calls);
            }
        };
    }

    private static ModelInfo.Pricing pricing(double inputPerMTok) {
        return new ModelInfo.Pricing("USD", inputPerMTok, 0.0, null, null);
    }

    private static ModelInfo model(ModelInfo.@Nullable Pricing pricing) {
        return new ModelInfo(
                "openai",
                "text-embedding-3-small",
                8192,
                0,
                ModelSize.SMALL,
                Set.of(),
                60,
                2,
                false,
                null,
                pricing,
                OutputTokenParam.MAX_TOKENS,
                Set.of(),
                null,
                null,
                false);
    }
}
