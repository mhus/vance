package de.mhus.vance.shared.llmusage;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Datenhoheit über {@link LlmUsageDocument}. Engines never persist
 * directly — they hand a {@link UsageWrite} to this service, which
 * computes costs from the rate snapshot and writes one row per call.
 *
 * <p>Cost math is intentionally simple — {@code tokens / 1_000_000 ×
 * rate} for each of input/output/cacheRead/cacheWrite. The four
 * components are persisted individually (in addition to the sum) so
 * reports can show breakdowns without joining back to the rate
 * snapshot.
 *
 * <p>Failures (Mongo unavailable, write rejected) are logged at WARN
 * and swallowed — usage tracking must never break a chat turn. The
 * affected call is simply unaccounted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmUsageService {

    private final LlmUsageRepository repository;

    /**
     * Persist a single LLM-call usage row. Returns the saved document
     * (with id) on success or {@code null} if persistence failed —
     * callers should not depend on a non-null return for control
     * flow.
     */
    public @Nullable LlmUsageDocument record(UsageWrite write) {
        try {
            LlmUsageDocument doc = build(write);
            return repository.save(doc);
        } catch (RuntimeException e) {
            log.warn("LlmUsage record failed tenant='{}' process='{}': {}",
                    write.tenantId(), write.processId(), e.toString());
            return null;
        }
    }

    /** Visible-for-testing — pure transform, no I/O. */
    static LlmUsageDocument build(UsageWrite w) {
        double costIn = costOf(w.tokensIn(), w.priceInputPerMTok());
        double costOut = costOf(w.tokensOut(), w.priceOutputPerMTok());
        double costCacheR = costOf(w.cacheReadTokens(), w.priceCacheReadPerMTok());
        double costCacheW = costOf(w.cacheWriteTokens(), w.priceCacheWritePerMTok());
        double costTotal = costIn + costOut + costCacheR + costCacheW;

        return LlmUsageDocument.builder()
                .tenantId(w.tenantId())
                .projectId(blankToNull(w.projectId()))
                .sessionId(blankToNull(w.sessionId()))
                .processId(w.processId())
                .recipeName(blankToNull(w.recipeName()))
                .engineName(blankToNull(w.engineName()))
                .providerInstance(blankToNull(w.providerInstance()))
                .providerType(blankToNull(w.providerType()))
                .providerModel(blankToNull(w.providerModel()))
                .modelAlias(blankToNull(w.modelAlias()))
                .tokensIn(w.tokensIn())
                .tokensOut(w.tokensOut())
                .cacheReadTokens(w.cacheReadTokens())
                .cacheWriteTokens(w.cacheWriteTokens())
                .priceInputPerMTok(w.priceInputPerMTok())
                .priceOutputPerMTok(w.priceOutputPerMTok())
                .priceCacheReadPerMTok(w.priceCacheReadPerMTok())
                .priceCacheWritePerMTok(w.priceCacheWritePerMTok())
                .currency(blankToNull(w.currency()))
                .costInput(costIn)
                .costOutput(costOut)
                .costCacheRead(costCacheR)
                .costCacheWrite(costCacheW)
                .costTotal(costTotal)
                .durationMs(w.durationMs())
                .contextWindowTokens(w.contextWindowTokens())
                .createdAt(w.createdAt())
                .build();
    }

    /** Cost helper: {@code tokens / 1_000_000 × rate}. Null rate ⇒ 0. */
    private static double costOf(int tokens, @Nullable Double ratePerMTok) {
        if (tokens <= 0 || ratePerMTok == null || ratePerMTok <= 0.0) {
            return 0.0;
        }
        return tokens / 1_000_000.0 * ratePerMTok;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Input bundle for {@link #record(UsageWrite)}. Use the builder so
     * future fields can be added without touching every callsite.
     */
    @lombok.Builder
    public record UsageWrite(
            String tenantId,
            @Nullable String projectId,
            @Nullable String sessionId,
            String processId,
            @Nullable String recipeName,
            @Nullable String engineName,
            @Nullable String providerInstance,
            @Nullable String providerType,
            @Nullable String providerModel,
            @Nullable String modelAlias,
            int tokensIn,
            int tokensOut,
            int cacheReadTokens,
            int cacheWriteTokens,
            @Nullable Double priceInputPerMTok,
            @Nullable Double priceOutputPerMTok,
            @Nullable Double priceCacheReadPerMTok,
            @Nullable Double priceCacheWritePerMTok,
            @Nullable String currency,
            long durationMs,
            @Nullable Integer contextWindowTokens,
            Instant createdAt) {}
}
