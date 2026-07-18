package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.progress.MetricsPayload;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the {@link LlmCallTracker} → {@link LlmUsageService} hand-off:
 * usage is persisted exactly when the model has a pricing block AND
 * the provider reported tokens. Other combinations must skip the
 * write so reports aren't polluted with zero-cost rows.
 *
 * <p>Also verifies that {@code MetricsPayload.contextWindowTokens}
 * is filled from {@link ModelInfo} so the client HUD can render
 * the fill ratio.
 */
class LlmCallTrackerUsageTest {

    private ProgressEmitter emitter;
    private MetricService metricService;
    private LlmUsageService usageService;
    private ModelCatalog modelCatalog;
    private LlmCallTracker tracker;

    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        emitter = mock(ProgressEmitter.class);
        metricService = mock(MetricService.class);
        usageService = mock(LlmUsageService.class);
        modelCatalog = mock(ModelCatalog.class);
        tracker = new LlmCallTracker(emitter, metricService, usageService, modelCatalog);

        // Micrometer doubles — no-op stubs, we don't assert on them.
        // MetricService takes String... varargs; Mockito wants the array form.
        lenient().when(metricService.counter(any(String.class), any(String[].class)))
                .thenReturn(mock(Counter.class));
        lenient().when(metricService.timer(any(String.class), any(String[].class)))
                .thenReturn(mock(Timer.class));
        lenient().when(metricService.summary(any(String.class), any(String[].class)))
                .thenReturn(mock(DistributionSummary.class));

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("demo");
        process.setSessionId("sess-1");
        process.setRecipeName("coding");
        process.setThinkEngine("frankie");
    }

    @Test
    void persistsUsage_whenModelHasPricingAndTokensReported() {
        ModelInfo info = modelInfoWithPricing();
        ChatResponse response = responseWith(10_000, 2_500);

        tracker.record(process, /*request*/ null, response, 1234L, "default:code", info);

        ArgumentCaptor<LlmUsageService.UsageWrite> cap =
                ArgumentCaptor.forClass(LlmUsageService.UsageWrite.class);
        verify(usageService, times(1)).record(cap.capture());
        LlmUsageService.UsageWrite w = cap.getValue();

        assertThat(w.tenantId()).isEqualTo("acme");
        assertThat(w.projectId()).isEqualTo("demo");
        assertThat(w.sessionId()).isEqualTo("sess-1");
        assertThat(w.processId()).isEqualTo("proc-1");
        assertThat(w.recipeName()).isEqualTo("coding");
        assertThat(w.engineName()).isEqualTo("frankie");
        assertThat(w.providerInstance()).isEqualTo("openai");
        assertThat(w.providerModel()).isEqualTo("glm-5.2");
        assertThat(w.modelAlias()).isEqualTo("default:code");
        assertThat(w.tokensIn()).isEqualTo(10_000);
        assertThat(w.tokensOut()).isEqualTo(2_500);
        assertThat(w.priceInputPerMTok()).isEqualTo(0.355);
        assertThat(w.priceOutputPerMTok()).isEqualTo(1.775);
        assertThat(w.currency()).isEqualTo("EUR");
        assertThat(w.contextWindowTokens()).isEqualTo(131_000);
        assertThat(w.durationMs()).isEqualTo(1234L);
    }

    @Test
    void skipsUsage_whenModelInfoIsNull() {
        ChatResponse response = responseWith(10_000, 2_500);

        tracker.record(process, /*request*/ null, response, 1234L, "default:code");

        verify(usageService, never()).record(any());
    }

    @Test
    void skipsUsage_whenPricingIsNull() {
        ModelInfo unpriced = new ModelInfo(
                "openai", "no-price",
                128_000, 4096,
                ModelSize.LARGE, Set.<ModelCapability>of(),
                60, 2, false,
                /*messageParser*/ null,
                /*pricing*/ null);
        ChatResponse response = responseWith(10_000, 2_500);

        tracker.record(process, /*request*/ null, response, 1234L, "default:code", unpriced);

        verify(usageService, never()).record(any());
    }

    @Test
    void skipsUsage_whenProviderReportsNoTokens() {
        ModelInfo info = modelInfoWithPricing();
        ChatResponse response = responseWith(0, 0);

        tracker.record(process, /*request*/ null, response, 1234L, "default:code", info);

        verify(usageService, never()).record(any());
    }

    @Test
    void metricsPayload_carriesContextWindowFromModelInfo() {
        ModelInfo info = modelInfoWithPricing();
        ChatResponse response = responseWith(10_000, 2_500);

        tracker.record(process, /*request*/ null, response, 1234L, "default:code", info);

        ArgumentCaptor<MetricsPayload> cap = ArgumentCaptor.forClass(MetricsPayload.class);
        verify(emitter).emitMetrics(any(), cap.capture());
        assertThat(cap.getValue().getContextWindowTokens()).isEqualTo(131_000);
        assertThat(cap.getValue().getLastCallTokensIn()).isEqualTo(10_000);
    }

    private static ModelInfo modelInfoWithPricing() {
        return new ModelInfo(
                "openai", "glm-5.2",
                131_000, 8192,
                ModelSize.LARGE, Set.<ModelCapability>of(),
                60, 2, false,
                /*messageParser*/ null,
                new ModelInfo.Pricing("EUR", 0.355, 1.775, /*cacheR*/ null, /*cacheW*/ null));
    }

    private static ChatResponse responseWith(int inputTokens, int outputTokens) {
        AiMessage msg = AiMessage.from("dummy");
        TokenUsage usage = new TokenUsage(inputTokens, outputTokens);
        return ChatResponse.builder().aiMessage(msg).tokenUsage(usage).build();
    }
}
