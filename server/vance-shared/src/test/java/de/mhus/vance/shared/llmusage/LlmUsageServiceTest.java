package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cost-derivation math + null-safety of the rate-snapshot
 * fields. The repository is mocked — the test pins the
 * pure-transform contract from {@link LlmUsageService#build} so cost
 * fields are reproducible for downstream report queries.
 */
class LlmUsageServiceTest {

    @Test
    void build_computesCostsFromTokensAndRates() {
        LlmUsageService.UsageWrite w = baseBuilder()
                .tokensIn(10_000)
                .tokensOut(2_500)
                .priceInputPerMTok(3.00)
                .priceOutputPerMTok(15.00)
                .currency("USD")
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);

        // 10_000 / 1_000_000 × 3.00 = 0.03
        assertThat(d.getCostInput()).isCloseTo(0.03, within(1e-9));
        // 2_500 / 1_000_000 × 15.00 = 0.0375
        assertThat(d.getCostOutput()).isCloseTo(0.0375, within(1e-9));
        assertThat(d.getCostCacheRead()).isZero();
        assertThat(d.getCostCacheWrite()).isZero();
        assertThat(d.getCostTotal()).isCloseTo(0.0675, within(1e-9));
        assertThat(d.getCurrency()).isEqualTo("USD");
    }

    @Test
    void build_includesCacheCostsWhenRatesProvided() {
        LlmUsageService.UsageWrite w = baseBuilder()
                .tokensIn(5_000)
                .tokensOut(1_000)
                .cacheReadTokens(20_000)
                .cacheWriteTokens(8_000)
                .priceInputPerMTok(3.00)
                .priceOutputPerMTok(15.00)
                .priceCacheReadPerMTok(0.30)
                .priceCacheWritePerMTok(3.75)
                .currency("USD")
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);

        assertThat(d.getCostInput()).isCloseTo(0.015, within(1e-9));
        assertThat(d.getCostOutput()).isCloseTo(0.015, within(1e-9));
        assertThat(d.getCostCacheRead()).isCloseTo(20_000 / 1_000_000.0 * 0.30, within(1e-9));
        assertThat(d.getCostCacheWrite()).isCloseTo(8_000 / 1_000_000.0 * 3.75, within(1e-9));
        assertThat(d.getCostTotal()).isCloseTo(
                d.getCostInput() + d.getCostOutput()
                        + d.getCostCacheRead() + d.getCostCacheWrite(),
                within(1e-9));
    }

    @Test
    void build_zerosCostWhenRateMissingForBucket() {
        LlmUsageService.UsageWrite w = baseBuilder()
                .tokensIn(10_000)
                .tokensOut(5_000)
                .cacheReadTokens(50_000)
                .priceInputPerMTok(3.00)
                .priceOutputPerMTok(15.00)
                // cacheReadPerMTok intentionally null → cacheRead cost stays 0
                .currency("USD")
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);
        assertThat(d.getCostInput()).isGreaterThan(0.0);
        assertThat(d.getCostOutput()).isGreaterThan(0.0);
        assertThat(d.getCostCacheRead()).isZero();
        // Total still reflects the priced buckets only.
        assertThat(d.getCostTotal()).isCloseTo(
                d.getCostInput() + d.getCostOutput(), within(1e-9));
    }

    @Test
    void build_preservesRateSnapshotForAuditTrail() {
        LlmUsageService.UsageWrite w = baseBuilder()
                .tokensIn(1)
                .tokensOut(1)
                .priceInputPerMTok(0.355)
                .priceOutputPerMTok(1.775)
                .currency("EUR")
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);
        assertThat(d.getPriceInputPerMTok()).isEqualTo(0.355);
        assertThat(d.getPriceOutputPerMTok()).isEqualTo(1.775);
        assertThat(d.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void record_returnsNullWhenRepositoryThrows() {
        LlmUsageRepository repo = mock(LlmUsageRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("mongo down"));

        LlmUsageService svc = new LlmUsageService(repo);
        LlmUsageDocument out = svc.record(baseBuilder()
                .tokensIn(100)
                .tokensOut(50)
                .priceInputPerMTok(3.00)
                .priceOutputPerMTok(15.00)
                .currency("USD")
                .build());

        // Tracking failure must not break the chat turn — service
        // swallows + logs, callers get null back.
        assertThat(out).isNull();
    }

    private static LlmUsageService.UsageWrite.UsageWriteBuilder baseBuilder() {
        return LlmUsageService.UsageWrite.builder()
                .tenantId("acme")
                .projectId("demo")
                .sessionId("sess-1")
                .processId("proc-1")
                .recipeName("coding")
                .engineName("frankie")
                .providerInstance("openai")
                .providerType("openai")
                .providerModel("glm-5.2")
                .modelAlias("default:code")
                .tokensIn(0)
                .tokensOut(0)
                .cacheReadTokens(0)
                .cacheWriteTokens(0)
                .durationMs(123)
                .contextWindowTokens(131_000)
                .createdAt(Instant.parse("2026-06-24T12:00:00Z"));
    }
}
