package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The one {@link UsageSink} that books into the ledger. Translates the
 * decorator's {@link UsageMeasurement} into a {@link
 * LlmUsageService.UsageWrite} and hands it over — no cost math here, that
 * belongs to the service that owns the collections.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerUsageSink implements UsageSink {

    private final LlmUsageService llmUsageService;

    @Override
    public void onCall(CallAttribution attribution, UsageMeasurement m) {
        if (m.isEmpty()) {
            // Nothing measured — a provider that reports no usage gives us
            // no row to write. The call still shows up in the audit log.
            return;
        }
        try {
            ModelInfo.Pricing p = m.pricing();
            llmUsageService.record(LlmUsageService.UsageWrite.builder()
                    .attribution(attribution)
                    .kind(m.kind())
                    .outcome(m.outcome())
                    .attempt(m.attempt())
                    .providerInstance(m.providerInstance())
                    .providerType(m.providerType())
                    .providerModel(m.providerModel())
                    .modelAlias(m.modelAlias())
                    .tokensIn(m.tokensIn())
                    .tokensOut(m.tokensOut())
                    .cacheReadTokens(m.cacheReadTokens())
                    .cacheWriteTokens(m.cacheWriteTokens())
                    .images(m.images())
                    .imageCost(m.imageCost())
                    .priceInputPerMTok(p == null ? null : p.inputPerMTok())
                    .priceOutputPerMTok(p == null ? null : p.outputPerMTok())
                    .priceCacheReadPerMTok(p == null ? null : p.cacheReadPerMTok())
                    .priceCacheWritePerMTok(p == null ? null : p.cacheWritePerMTok())
                    .currency(m.resolvedCurrency())
                    .durationMs(m.durationMs())
                    .contextWindowTokens(m.contextWindowTokens())
                    .createdAt(java.time.Instant.now())
                    .build());
        } catch (RuntimeException e) {
            // Belt and braces — LlmUsageService already swallows its own
            // failures, but a sink that throws would surface inside a chat
            // turn, which is exactly what must not happen.
            log.warn("Usage sink failed tenant='{}' caller='{}': {}",
                    attribution.tenantId(), attribution.caller(), e.toString());
        }
    }
}
