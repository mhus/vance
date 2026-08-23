package de.mhus.vance.shared.llmusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Cost derivation and the pricing-coverage predicate — the two pieces the
 * invoice depends on. Both are pure transforms, so they are pinned without
 * a Mongo in sight.
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
    void build_carriesAttributionAndCallKind() {
        LlmUsageService.UsageWrite w = baseBuilder()
                .tokensIn(10)
                .tokensOut(5)
                .outcome(UsageOutcome.FAILED)
                .attempt(3)
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);
        assertThat(d.getTenantId()).isEqualTo("acme");
        assertThat(d.getProjectId()).isEqualTo("demo");
        assertThat(d.getEngineName()).isEqualTo("frankie");
        assertThat(d.getRecipeName()).isEqualTo("coding");
        assertThat(d.getKind()).isEqualTo(UsageKind.CHAT);
        assertThat(d.getOutcome()).isEqualTo(UsageOutcome.FAILED);
        assertThat(d.getAttempt()).isEqualTo(3);
    }

    @Test
    void imageCall_isPricedPerImageNotPerToken() {
        LlmUsageService.UsageWrite w = LlmUsageService.UsageWrite.builder()
                .attribution(CallAttribution.ofService(
                        "acme", "demo", LlmUsageService.CALLER_FENCHURCH))
                .kind(UsageKind.IMAGE)
                .images(1)
                .imageCost(0.039)
                .currency("USD")
                .createdAt(Instant.parse("2026-06-24T12:00:00Z"))
                .build();

        LlmUsageDocument d = LlmUsageService.build(w);
        assertThat(d.getCostTotal()).isCloseTo(0.039, within(1e-9));
        assertThat(d.getImages()).isEqualTo(1);
        assertThat(w.priced()).isTrue();
    }

    @Test
    void priced_isFalseWhenTheModelHasNoRateAtAll() {
        // The distinction that keeps the invoice honest: a model with no
        // `pricing:` block is unknown, not free. The report has to say so
        // instead of adding a silent zero into the total.
        LlmUsageService.UsageWrite unpriced = baseBuilder()
                .tokensIn(10_000)
                .tokensOut(5_000)
                .build();
        assertThat(unpriced.priced()).isFalse();

        // A local model declares zero explicitly, which counts as priced.
        LlmUsageService.UsageWrite explicitlyFree = baseBuilder()
                .tokensIn(10_000)
                .tokensOut(5_000)
                .priceInputPerMTok(0.0)
                .priceOutputPerMTok(0.0)
                .currency("EUR")
                .build();
        assertThat(explicitlyFree.priced()).isTrue();
        assertThat(LlmUsageService.build(explicitlyFree).getCostTotal()).isZero();
    }

    @Test
    void bucketId_isStableAndSeparatesAdjacentKeyFields() {
        String a = LlmUsageDailyDocument.bucketId(
                "acme", "2026-06-24", "demo", "arthur", "chat", "gpt-5", "USD", UsageKind.CHAT);
        String b = LlmUsageDailyDocument.bucketId(
                "acme", "2026-06-24", "demo", "arthur", "chat", "gpt-5", "USD", UsageKind.CHAT);
        assertThat(a).isEqualTo(b).startsWith("usage_");

        // Without the \0 separator these two would hash identically.
        String left = LlmUsageDailyDocument.bucketId(
                "acme", "2026-06-24", "de", "moarthur", "chat", "gpt-5", "USD", UsageKind.CHAT);
        assertThat(left).isNotEqualTo(a);

        // Kind is part of the key — same model, different unit.
        String image = LlmUsageDailyDocument.bucketId(
                "acme", "2026-06-24", "demo", "arthur", "chat", "gpt-5", "USD", UsageKind.IMAGE);
        assertThat(image).isNotEqualTo(a);
    }

    @Test
    void dayBucket_isUtcAndExpiryAnchorsOnTheDayNotTheWrite() {
        // 00:30 in UTC+2 is still the previous UTC day — the bucket has to
        // follow UTC, or a late-evening call lands in tomorrow's invoice.
        assertThat(LlmUsageDailyDocument.dayOf(Instant.parse("2026-06-24T22:30:00Z")))
                .isEqualTo("2026-06-24");
        assertThat(LlmUsageDailyDocument.dayStart("2026-06-24"))
                .isEqualTo(Instant.parse("2026-06-24T00:00:00Z"));
    }

    private static LlmUsageService.UsageWrite.UsageWriteBuilder baseBuilder() {
        return LlmUsageService.UsageWrite.builder()
                .attribution(new CallAttribution(
                        "acme", "demo", "sess-1", "proc-1", "frankie", "coding"))
                .providerInstance("openai")
                .providerType("openai")
                .providerModel("glm-5.2")
                .modelAlias("default:code")
                .durationMs(123)
                .contextWindowTokens(131_000)
                .createdAt(Instant.parse("2026-06-24T12:00:00Z"));
    }
}
